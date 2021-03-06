/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.index.internal.gbptree;

import org.apache.commons.lang3.mutable.MutableLong;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

import java.io.File;
import java.io.IOException;
import java.nio.file.OpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.neo4j.collection.primitive.Primitive;
import org.neo4j.collection.primitive.PrimitiveLongCollections;
import org.neo4j.collection.primitive.PrimitiveLongSet;
import org.neo4j.cursor.RawCursor;
import org.neo4j.graphdb.mockfs.EphemeralFileSystemAbstraction;
import org.neo4j.index.internal.gbptree.GBPTree.Monitor;
import org.neo4j.io.pagecache.DelegatingPageCache;
import org.neo4j.io.pagecache.DelegatingPagedFile;
import org.neo4j.io.pagecache.IOLimiter;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.PagedFile;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.test.Barrier;
import org.neo4j.test.rule.PageCacheRule;
import org.neo4j.test.rule.RandomRule;
import org.neo4j.test.rule.TestDirectory;
import org.neo4j.test.rule.fs.DefaultFileSystemRule;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.rules.RuleChain.outerRule;
import static org.neo4j.index.internal.gbptree.GBPTree.NO_HEADER;
import static org.neo4j.index.internal.gbptree.GBPTree.NO_MONITOR;
import static org.neo4j.index.internal.gbptree.ThrowingRunnable.throwing;
import static org.neo4j.io.pagecache.IOLimiter.unlimited;
import static org.neo4j.io.pagecache.PagedFile.PF_SHARED_WRITE_LOCK;
import static org.neo4j.test.rule.PageCacheRule.config;

@SuppressWarnings( "EmptyTryBlock" )
public class GBPTreeTest
{
    private final DefaultFileSystemRule fs = new DefaultFileSystemRule();
    private final TestDirectory directory = TestDirectory.testDirectory( getClass(), fs.get() );
    private final PageCacheRule pageCacheRule = new PageCacheRule( config().withAccessChecks( true ) );
    private final RandomRule random = new RandomRule();

    @Rule
    public final RuleChain rules = outerRule( fs ).around( directory ).around( pageCacheRule ).around( random );

    private PageCache pageCache;
    private File indexFile;
    private static final Layout<MutableLong,MutableLong> layout = new SimpleLongLayout();
    private ExecutorService executor;

    @Before
    public void setUp()
    {
        executor = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
        indexFile = directory.file( "index" );
    }

    @After
    public void teardown()
    {
        executor.shutdown();
    }

    /* Meta and state page tests */

    @Test
    public void shouldReadWrittenMetaData() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong,MutableLong> ignored = index().build() )
        {   // open/close is enough
        }

        // WHEN
        try ( GBPTree<MutableLong,MutableLong> ignored = index().build() )
        {   // open/close is enough
        }

        // THEN being able to open validates that the same meta data was read
    }

    @Test
    public void shouldFailToOpenOnDifferentMetaData() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong,MutableLong> ignored = index().withPageCachePageSize( 1024 ).build() )
        {   // Open/close is enough
        }

        // WHEN
        SimpleLongLayout otherLayout = new SimpleLongLayout( "Something else" );
        try ( GBPTree<MutableLong,MutableLong> ignored = index().with( otherLayout ).build() )
        {
            fail( "Should not load" );
        }
        catch ( MetadataMismatchException e )
        {
            // THEN good
        }

        // THEN being able to open validates that the same meta data was read
        // the test also closes the index afterwards
    }

    @Test
    public void shouldFailToOpenOnDifferentLayout() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong,MutableLong> ignored = index().build() )
        {   // Open/close is enough
        }

        // WHEN
        SimpleLongLayout otherLayout = new SimpleLongLayout()
        {
            @Override
            public long identifier()
            {
                return 123456;
            }
        };
        try ( GBPTree<MutableLong,MutableLong> ignored = index().with( otherLayout ).build() )
        {

            fail( "Should not load" );
        }
        catch ( MetadataMismatchException e )
        {
            // THEN good
        }
    }

    @Test
    public void shouldFailToOpenOnDifferentMajorVersion() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong,MutableLong> ignored = index().withPageCachePageSize( 1024 ).build() )
        {   // Open/close is enough
        }

        // WHEN
        SimpleLongLayout otherLayout = new SimpleLongLayout()
        {
            @Override
            public int majorVersion()
            {
                return super.majorVersion() + 1;
            }
        };
        try ( GBPTree<MutableLong,MutableLong> ignored = index().with( otherLayout ).build() )
        {
            fail( "Should not load" );
        }
        catch ( MetadataMismatchException e )
        {
            // THEN good
        }
    }

    @Test
    public void shouldFailToOpenOnDifferentMinorVersion() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong,MutableLong> ignored = index().build() )
        {   // Open/close is enough
        }

        // WHEN
        SimpleLongLayout otherLayout = new SimpleLongLayout()
        {
            @Override
            public int minorVersion()
            {
                return super.minorVersion() + 1;
            }
        };
        try ( GBPTree<MutableLong,MutableLong> ignored = index().with( otherLayout ).build() )
        {
            fail( "Should not load" );
        }
        catch ( MetadataMismatchException e )
        {
            // THEN good
        }
    }

    @Test
    public void shouldFailOnOpenWithDifferentPageSize() throws Exception
    {
        // GIVEN
        int pageSize = 1024;
        try ( GBPTree<MutableLong,MutableLong> ignored = index().withPageCachePageSize( pageSize ).build() )
        {   // Open/close is enough
        }

        // WHEN
        try ( GBPTree<MutableLong,MutableLong> ignored = index().withPageCachePageSize( pageSize / 2 ).build() )
        {
            fail( "Should not load" );
        }
        catch ( MetadataMismatchException e )
        {
            // THEN good
            assertThat( e.getMessage(), containsString( "page size" ) );
        }
    }

    @Test
    public void shouldFailOnStartingWithPageSizeLargerThanThatOfPageCache() throws Exception
    {
        // WHEN
        int pageCachePageSize = 512;
        try ( GBPTree<MutableLong,MutableLong> ignored = index()
                .withPageCachePageSize( pageCachePageSize )
                .withIndexPageSize( 2 * pageCachePageSize )
                .build() )
        {
            fail( "Shouldn't have been created" );
        }
        catch ( MetadataMismatchException e )
        {
            // THEN good
            assertThat( e.getMessage(), containsString( "page size" ) );
        }
    }

    @Test
    public void shouldMapIndexFileWithProvidedPageSizeIfLessThanOrEqualToCachePageSize() throws Exception
    {
        // WHEN
        int pageCachePageSize = 1024;
        try ( GBPTree<MutableLong,MutableLong> ignored = index()
                .withPageCachePageSize( pageCachePageSize )
                .withIndexPageSize( pageCachePageSize / 2 )
                .build() )
        {
            // Good
        }
    }

    @Test
    public void shouldFailWhenTryingToRemapWithPageSizeLargerThanCachePageSize() throws Exception
    {
        // WHEN
        int pageCachePageSize = 1024;
        try ( GBPTree<MutableLong,MutableLong> ignored = index().withPageCachePageSize( pageCachePageSize ).build() )
        {
            // Good
        }

        try ( GBPTree<MutableLong,MutableLong> ignored = index()
                .withPageCachePageSize( pageCachePageSize / 2 )
                .withIndexPageSize( pageCachePageSize )
                .build() )
        {
            fail( "Expected to fail" );
        }
        catch ( MetadataMismatchException e )
        {
            // THEN Good
            assertThat( e.getMessage(), containsString( "page size" ) );
        }
    }

    @Test
    public void shouldRemapFileIfMappedWithPageSizeLargerThanCreationSize() throws Exception
    {
        // WHEN
        int pageSize = 1024;
        List<Long> expectedData = new ArrayList<>();
        for ( long i = 0; i < 100; i++ )
        {
            expectedData.add( i );
        }
        try ( GBPTree<MutableLong,MutableLong> index = index()
                .withPageCachePageSize( pageSize )
                .withIndexPageSize( pageSize / 2 )
                .build() )
        {
            // Insert some data
            try ( Writer<MutableLong,MutableLong> writer = index.writer() )
            {
                MutableLong key = new MutableLong();
                MutableLong value = new MutableLong();

                for ( Long insert : expectedData )
                {
                    key.setValue( insert );
                    value.setValue( insert );
                    writer.put( key, value );
                }
            }
            index.checkpoint( unlimited() );
        }

        // THEN
        try ( GBPTree<MutableLong,MutableLong> index = index().withPageCachePageSize( pageSize ).build() )
        {
            MutableLong fromInclusive = new MutableLong( 0L );
            MutableLong toExclusive = new MutableLong( 200L );
            try ( RawCursor<Hit<MutableLong,MutableLong>,IOException> seek = index.seek( fromInclusive, toExclusive ) )
            {
                int i = 0;
                while ( seek.next() )
                {
                    Hit<MutableLong,MutableLong> hit = seek.get();
                    assertEquals( hit.key().getValue(), expectedData.get( i ) );
                    assertEquals( hit.value().getValue(), expectedData.get( i ) );
                    i++;
                }
            }
        }
    }

    @Test
    public void shouldFailWhenTryingToOpenWithDifferentFormatVersion() throws Exception
    {
        // GIVEN
        int pageSize = 256;
        try ( GBPTree<MutableLong,MutableLong> ignored = index().withPageCachePageSize( pageSize ).build() )
        {   // Open/close is enough
        }
        setFormatVersion( pageSize, GBPTree.FORMAT_VERSION - 1 );

        try
        {
            // WHEN
            index().withPageCachePageSize( pageSize ).build();
            fail( "Should have failed" );
        }
        catch ( MetadataMismatchException e )
        {
            // THEN good
        }
    }

    @Test
    public void shouldReturnNoResultsOnEmptyIndex() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong,MutableLong> index = index().build() )
        {

            // WHEN
            RawCursor<Hit<MutableLong,MutableLong>,IOException> result =
                    index.seek( new MutableLong( 0 ), new MutableLong( 10 ) );

            // THEN
            assertFalse( result.next() );
        }
    }

    /* Lifecycle tests */

    @Test
    public void shouldNotBeAbleToAcquireModifierTwice() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong,MutableLong> index = index().build() )
        {
            Writer<MutableLong,MutableLong> writer = index.writer();

            // WHEN
            try
            {
                index.writer();
                fail( "Should have failed" );
            }
            catch ( IllegalStateException e )
            {
                // THEN good
            }

            // Should be able to close old writer
            writer.close();
            // And open and closing a new one
            index.writer().close();
        }
    }

    @Test
    public void shouldNotAllowClosingWriterMultipleTimes() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong,MutableLong> index = index().build() )
        {
            Writer<MutableLong,MutableLong> writer = index.writer();
            writer.put( new MutableLong( 0 ), new MutableLong( 1 ) );
            writer.close();

            try
            {
                // WHEN
                writer.close();
                fail( "Should have failed" );
            }
            catch ( IllegalStateException e )
            {
                // THEN
                assertThat( e.getMessage(), containsString( "already closed" ) );
            }
        }
    }

    @Test
    public void failureDuringInitializeWriterShouldNotFailNextInitialize() throws Exception
    {
        // GIVEN
        IOException no = new IOException( "No" );
        AtomicBoolean throwOnNextIO = new AtomicBoolean();
        PageCache controlledPageCache = pageCacheThatThrowExceptionWhenToldTo( no, throwOnNextIO );
        try ( GBPTree<MutableLong,MutableLong> index = index().with( controlledPageCache ).build() )
        {
            // WHEN
            assert throwOnNextIO.compareAndSet( false, true );
            try ( Writer<MutableLong,MutableLong> ignored = index.writer() )
            {
                fail( "Expected to throw" );
            }
            catch ( IOException e )
            {
                assertSame( no, e );
            }

            // THEN
            try ( Writer<MutableLong,MutableLong> writer = index.writer() )
            {
                writer.put( new MutableLong( 1 ), new MutableLong( 1 ) );
            }
        }
    }

    @Test
    public void shouldAllowClosingTreeMultipleTimes() throws Exception
    {
        // GIVEN
        GBPTree<MutableLong,MutableLong> index = index().build();

        // WHEN
        index.close();

        // THEN
        index.close(); // should be OK
    }

    /* Header test */

    @Test
    public void shouldPutHeaderDataInCheckPoint() throws Exception
    {
        BiConsumer<GBPTree<MutableLong,MutableLong>,byte[]> beforeClose = ( index, expected ) ->
        {
            ThrowingRunnable throwingRunnable = () ->
                    index.checkpoint( unlimited(), cursor -> cursor.putBytes( expected ) );
            ThrowingRunnable.throwing( throwingRunnable ).run();
        };
        verifyHeaderDataAfterClose( beforeClose );
    }

    @Test
    public void shouldCarryOverHeaderDataInCheckPoint() throws Exception
    {
        BiConsumer<GBPTree<MutableLong,MutableLong>,byte[]> beforeClose = ( index, expected ) ->
        {
            ThrowingRunnable throwingRunnable = () ->
            {
                index.checkpoint( unlimited(), cursor -> cursor.putBytes( expected ) );
                insert( index, 0, 1 );

                // WHEN
                // Should carry over header data
                index.checkpoint( unlimited() );
            };
            ThrowingRunnable.throwing( throwingRunnable ).run();
        };
        verifyHeaderDataAfterClose( beforeClose );
    }

    @Test
    public void shouldCarryOverHeaderDataOnDirtyClose() throws Exception
    {
        BiConsumer<GBPTree<MutableLong,MutableLong>,byte[]> beforeClose = ( index, expected ) ->
        {
            ThrowingRunnable throwingRunnable = () ->
            {
                index.checkpoint( unlimited(), cursor -> cursor.putBytes( expected ) );
                insert( index, 0, 1 );

                // No checkpoint
            };
            ThrowingRunnable.throwing( throwingRunnable ).run();
        };
        verifyHeaderDataAfterClose( beforeClose );
    }

    @Test
    public void shouldReplaceHeaderDataInNextCheckPoint() throws Exception
    {
        BiConsumer<GBPTree<MutableLong,MutableLong>,byte[]> beforeClose = ( index, expected ) ->
        {
            ThrowingRunnable throwingRunnable = () ->
            {
                index.checkpoint( unlimited(), cursor -> cursor.putBytes( expected ) );
                ThreadLocalRandom.current().nextBytes( expected );
                index.checkpoint( unlimited(), cursor -> cursor.putBytes( expected ) );
            };
            ThrowingRunnable.throwing( throwingRunnable ).run();
        };

        verifyHeaderDataAfterClose( beforeClose );
    }

    private void verifyHeaderDataAfterClose( BiConsumer<GBPTree<MutableLong,MutableLong>,byte[]> beforeClose )
            throws IOException
    {
        byte[] expectedHeader = new byte[12];
        ThreadLocalRandom.current().nextBytes( expectedHeader );

        // GIVEN
        try ( GBPTree<MutableLong,MutableLong> index = index().build() )
        {
            beforeClose.accept( index, expectedHeader );
        }

        // WHEN
        byte[] readHeader = new byte[expectedHeader.length];
        AtomicInteger length = new AtomicInteger();
        Header.Reader headerReader = ( cursor, len ) ->
        {
            length.set( len );
            cursor.getBytes( readHeader );
        };
        try ( GBPTree<MutableLong,MutableLong> ignored = index().with( headerReader ).build() )
        {   // open/close is enough to read header
        }

        // THEN
        assertEquals( expectedHeader.length, length.get() );
        assertArrayEquals( expectedHeader, readHeader );
    }

    /* Mutex tests */

    @Test( timeout = 5_000L )
    public void checkPointShouldLockOutWriter() throws Exception
    {
        // GIVEN
        CheckpointControlledMonitor monitor = new CheckpointControlledMonitor();
        try ( GBPTree<MutableLong,MutableLong> index = index().with( monitor ).build() )
        {
            long key = 10;
            try ( Writer<MutableLong,MutableLong> writer = index.writer() )
            {
                writer.put( new MutableLong( key ), new MutableLong( key ) );
            }

            // WHEN
            monitor.enabled = true;
            Future<?> checkpoint = executor.submit( throwing( () -> index.checkpoint( unlimited() ) ) );
            monitor.barrier.awaitUninterruptibly();
            // now we're in the smack middle of a checkpoint
            Future<?> writerClose = executor.submit( throwing( () -> index.writer().close() ) );

            // THEN
            shouldWait( writerClose );
            monitor.barrier.release();

            writerClose.get();
            checkpoint.get();
        }
    }

    @Test( timeout = 5_000L )
    public void checkPointShouldWaitForWriter() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong,MutableLong> index = index().build() )
        {
            // WHEN
            Barrier.Control barrier = new Barrier.Control();
            Future<?> write = executor.submit( throwing( () ->
            {
                try ( Writer<MutableLong,MutableLong> writer = index.writer() )
                {
                    writer.put( new MutableLong( 1 ), new MutableLong( 1 ) );
                    barrier.reached();
                }
            } ) );
            barrier.awaitUninterruptibly();
            Future<?> checkpoint = executor.submit( throwing( () -> index.checkpoint( unlimited() ) ) );
            shouldWait( checkpoint );

            // THEN
            barrier.release();
            checkpoint.get();
            write.get();
        }
    }

    @Test( timeout = 5_000L )
    public void closeShouldLockOutWriter() throws Exception
    {
        // GIVEN
        AtomicBoolean enabled = new AtomicBoolean();
        Barrier.Control barrier = new Barrier.Control();
        PageCache pageCacheWithBarrier = pageCacheWithBarrierInClose( enabled, barrier );
        GBPTree<MutableLong,MutableLong> index = index().with( pageCacheWithBarrier ).build();
        long key = 10;
        try ( Writer<MutableLong,MutableLong> writer = index.writer() )
        {
            writer.put( new MutableLong( key ), new MutableLong( key ) );
        }

        // WHEN
        enabled.set( true );
        Future<?> close = executor.submit( throwing( index::close ) );
        barrier.awaitUninterruptibly();
        // now we're in the smack middle of a close/checkpoint
        AtomicReference<Exception> writerError = new AtomicReference<>();
        Future<?> write = executor.submit( () ->
        {
            try
            {
                index.writer().close();
            }
            catch ( Exception e )
            {
                writerError.set( e );
            }
        } );

        shouldWait( write );
        barrier.release();

        // THEN
        write.get();
        close.get();
        assertTrue( "Writer should not be able to acquired after close",
                writerError.get() instanceof IllegalStateException );
    }

    private PageCache pageCacheWithBarrierInClose( final AtomicBoolean enabled, final Barrier.Control barrier )
    {
        return new DelegatingPageCache( createPageCache( 1024 ) )
        {
            @Override
            public PagedFile map( File file, int pageSize, OpenOption... openOptions ) throws IOException
            {
                return new DelegatingPagedFile( super.map( file, pageSize, openOptions ) )
                {
                    @Override
                    public void close() throws IOException
                    {
                        if ( enabled.get() )
                        {
                            barrier.reached();
                        }
                        super.close();
                    }
                };
            }
        };
    }

    @Test( timeout = 5_000L )
    public void writerShouldLockOutClose() throws Exception
    {
        // GIVEN
        GBPTree<MutableLong,MutableLong> index = index().build();

        // WHEN
        Barrier.Control barrier = new Barrier.Control();
        Future<?> write = executor.submit( throwing( () ->
        {
            try ( Writer<MutableLong,MutableLong> writer = index.writer() )
            {
                writer.put( new MutableLong( 1 ), new MutableLong( 1 ) );
                barrier.reached();
            }
        } ) );
        barrier.awaitUninterruptibly();
        Future<?> close = executor.submit( throwing( index::close ) );
        shouldWait( close );

        // THEN
        barrier.release();
        close.get();
        write.get();
    }

    @Test( timeout = 5_000L )
    public void cleanJobShouldLockOutCheckpoint() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong,MutableLong> index = index().build() )
        {
            // Make dirty
            index.writer().close();
        }

        RecoveryCleanupWorkCollector cleanupWork = new ControlledRecoveryCleanupWorkCollector();
        CleanJobControlledMonitor monitor = new CleanJobControlledMonitor();
        try ( GBPTree<MutableLong,MutableLong> index = index().with( monitor ).with( cleanupWork ).build() )
        {
            // WHEN
            // Cleanup not finished
            Future<?> cleanup = executor.submit( throwing( cleanupWork::start ) );
            monitor.barrier.awaitUninterruptibly();
            index.writer().close();

            // THEN
            Future<?> checkpoint = executor.submit( throwing( () -> index.checkpoint( IOLimiter.unlimited() ) ) );
            shouldWait( checkpoint );

            monitor.barrier.release();
            cleanup.get();
            checkpoint.get();
        }
    }

    @Test( timeout = 5_000L )
    public void cleanJobShouldLockOutClose() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong,MutableLong> index = index().build() )
        {
            // Make dirty
            index.writer().close();
        }

        RecoveryCleanupWorkCollector cleanupWork = new ControlledRecoveryCleanupWorkCollector();
        CleanJobControlledMonitor monitor = new CleanJobControlledMonitor();
        GBPTree<MutableLong,MutableLong> index = index().with( monitor ).with( cleanupWork ).build();

        // WHEN
        // Cleanup not finished
        Future<?> cleanup = executor.submit( throwing( cleanupWork::start ) );
        monitor.barrier.awaitUninterruptibly();

        // THEN
        Future<?> close = executor.submit( throwing( index::close ) );
        shouldWait( close );

        monitor.barrier.release();
        cleanup.get();
        close.get();
    }

    @Test( timeout = 5_000L )
    public void cleanJobShouldNotLockOutWriter() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong,MutableLong> index = index().build() )
        {
            // Make dirty
            index.writer().close();
        }

        RecoveryCleanupWorkCollector cleanupWork = new ControlledRecoveryCleanupWorkCollector();
        CleanJobControlledMonitor monitor = new CleanJobControlledMonitor();
        try ( GBPTree<MutableLong,MutableLong> index = index().with( monitor ).with( cleanupWork ).build() )
        {
            // WHEN
            // Cleanup not finished
            Future<?> cleanup = executor.submit( throwing( cleanupWork::start ) );
            monitor.barrier.awaitUninterruptibly();

            // THEN
            Future<?> writer = executor.submit( throwing( () -> index.writer().close() ) );
            writer.get();

            monitor.barrier.release();
            cleanup.get();
        }
    }

    @Test
    public void writerShouldNotLockOutCleanJob() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong,MutableLong> index = index().build() )
        {
            // Make dirty
            index.writer().close();
        }

        RecoveryCleanupWorkCollector cleanupWork = new ControlledRecoveryCleanupWorkCollector();
        try ( GBPTree<MutableLong,MutableLong> index = index().with( cleanupWork ).build() )
        {
            // WHEN
            try ( Writer<MutableLong,MutableLong> writer = index.writer() )
            {
                // THEN
                Future<?> cleanup = executor.submit( throwing( cleanupWork::start ) );
                // Move writer to let cleaner pass
                writer.put( new MutableLong( 1 ), new MutableLong( 1 ) );
                cleanup.get();
            }
        }
    }

    /* Insertion and read tests */

    @Test
    public void shouldSeeSimpleInsertions() throws Exception
    {
        try ( GBPTree<MutableLong,MutableLong> index = index().build() )
        {
            int count = 1000;
            try ( Writer<MutableLong,MutableLong> writer = index.writer() )
            {
                for ( int i = 0; i < count; i++ )
                {
                    writer.put( new MutableLong( i ), new MutableLong( i ) );
                }
            }

            try ( RawCursor<Hit<MutableLong,MutableLong>,IOException> cursor =
                          index.seek( new MutableLong( 0 ), new MutableLong( Long.MAX_VALUE ) ) )
            {
                for ( int i = 0; i < count; i++ )
                {
                    assertTrue( cursor.next() );
                    assertEquals( i, cursor.get().key().longValue() );
                }
                assertFalse( cursor.next() );
            }
        }
    }

    @Test
    public void shouldSeeSimpleInsertionsWithExactMatch() throws Exception
    {
        try ( GBPTree<MutableLong,MutableLong> index = index().build() )
        {
            int count = 1000;
            try ( Writer<MutableLong,MutableLong> writer = index.writer() )
            {
                for ( int i = 0; i < count; i++ )
                {
                    writer.put( new MutableLong( i ), new MutableLong( i ) );
                }
            }

            for ( int i = 0; i < count; i++ )
            {
                try ( RawCursor<Hit<MutableLong,MutableLong>,IOException> cursor =
                              index.seek( new MutableLong( i ), new MutableLong( i ) ) )
                {
                    assertTrue( cursor.next() );
                    assertEquals( i, cursor.get().key().longValue() );
                    assertFalse( cursor.next() );
                }
            }
        }
    }

    /* Randomized tests */

    @Test
    public void shouldSplitCorrectly() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong,MutableLong> index = index().build() )
        {
            // WHEN
            int count = 1_000;
            PrimitiveLongSet seen = Primitive.longSet( count );
            try ( Writer<MutableLong,MutableLong> writer = index.writer() )
            {
                for ( int i = 0; i < count; i++ )
                {
                    MutableLong key;
                    do
                    {
                        key = new MutableLong( random.nextInt( 100_000 ) );
                    }
                    while ( !seen.add( key.longValue() ) );
                    MutableLong value = new MutableLong( i );
                    writer.put( key, value );
                    seen.add( key.longValue() );
                }
            }

            // THEN
            try ( RawCursor<Hit<MutableLong,MutableLong>,IOException> cursor =
                          index.seek( new MutableLong( 0 ), new MutableLong( Long.MAX_VALUE ) ) )
            {
                long prev = -1;
                while ( cursor.next() )
                {
                    MutableLong hit = cursor.get().key();
                    if ( hit.longValue() < prev )
                    {
                        fail( hit + " smaller than prev " + prev );
                    }
                    prev = hit.longValue();
                    assertTrue( seen.remove( hit.longValue() ) );
                }

                if ( !seen.isEmpty() )
                {
                    fail( "expected hits " + Arrays.toString( PrimitiveLongCollections.asArray( seen.iterator() ) ) );
                }
            }
        }
    }

    /* Checkpoint tests */

    @Test
    public void shouldCheckpointAfterInitialCreation() throws Exception
    {
        // GIVEN
        CheckpointCounter checkpointCounter = new CheckpointCounter();

        // WHEN
        GBPTree<MutableLong,MutableLong> index = index().with( checkpointCounter ).build();

        // THEN
        assertEquals( 1, checkpointCounter.count() );
        index.close();
    }

    @Test
    public void shouldNotCheckpointOnCloseIfNoChangesHappened() throws Exception
    {
        // GIVEN
        CheckpointCounter checkpointCounter = new CheckpointCounter();

        // WHEN
        try ( GBPTree<MutableLong,MutableLong> index = index().with( checkpointCounter ).build() )
        {
            checkpointCounter.reset();
            try ( Writer<MutableLong,MutableLong> writer = index.writer() )
            {
                writer.put( new MutableLong( 0 ), new MutableLong( 1 ) );
            }
            index.checkpoint( unlimited() );
            assertEquals( 1, checkpointCounter.count() );
        }

        // THEN
        assertEquals( 1, checkpointCounter.count() );
    }

    @Test
    public void mustNotSeeUpdatesThatWasNotCheckpointed() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong, MutableLong> index = index().build() )
        {
            insert( index, 0, 1 );

            // WHEN
            // No checkpoint before close
        }

        // THEN
        try ( GBPTree<MutableLong, MutableLong> index = index().build() )
        {
            MutableLong from = new MutableLong( Long.MIN_VALUE );
            MutableLong to = new MutableLong( Long.MAX_VALUE );
            try ( RawCursor<Hit<MutableLong,MutableLong>,IOException> seek = index.seek( from, to ) )
            {
                assertFalse( seek.next() );
            }
        }
    }

    @Test
    public void mustSeeUpdatesThatWasCheckpointed() throws Exception
    {
        // GIVEN
        int key = 1;
        int value = 2;
        try ( GBPTree<MutableLong, MutableLong> index = index().build() )
        {
            insert( index, key, value );

            // WHEN
            index.checkpoint( unlimited() );
        }

        // THEN
        try ( GBPTree<MutableLong, MutableLong> index = index().build() )
        {
            MutableLong from = new MutableLong( Long.MIN_VALUE );
            MutableLong to = new MutableLong( Long.MAX_VALUE );
            try ( RawCursor<Hit<MutableLong,MutableLong>,IOException> seek = index.seek( from, to ) )
            {
                assertTrue( seek.next() );
                assertEquals( key, seek.get().key().longValue() );
                assertEquals( value, seek.get().value().longValue() );
            }
        }
    }

    @Test
    public void mustBumpUnstableGenerationOnOpen() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong, MutableLong> index = index().build() )
        {
            insert( index, 0, 1 );

            // no checkpoint
        }

        // WHEN
        SimpleCleanupMonitor monitor = new SimpleCleanupMonitor();
        try ( GBPTree<MutableLong, MutableLong> ignore = index().with( monitor ).build() )
        {
        }

        // THEN
        assertTrue( "Expected monitor to get recovery complete message", monitor.cleanupFinished );
        assertEquals( "Expected index to have exactly 1 crash pointer from root to successor of root",
                1, monitor.numberOfCleanedCrashPointers );
        assertEquals( "Expected index to have exactly 2 tree node pages, root and successor of root",
                2, monitor.numberOfPagesVisited ); // Root and successor of root
    }

    /* Dirty state tests */

    @Test
    public void indexMustBeCleanOnFirstInitialization() throws Exception
    {
        // GIVEN
        assertFalse( fs.get().fileExists( indexFile ) );
        MonitorDirty monitorDirty = new MonitorDirty();

        // WHEN
        try ( GBPTree<MutableLong, MutableLong> ignored = index().with( monitorDirty ).build() )
        {
        }

        // THEN
        assertTrue( "Expected to be clean on start", monitorDirty.cleanOnStart() );
    }

    @Test
    public void indexMustBeCleanWhenClosedWithoutAnyChanges() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong, MutableLong> ignored = index().build() )
        {
        }

        // WHEN
        MonitorDirty monitorDirty = new MonitorDirty();
        try ( GBPTree<MutableLong, MutableLong> ignored = index().with( monitorDirty ).build() )
        {
        }

        // THEN
        assertTrue( "Expected to be clean on start after close with no changes", monitorDirty.cleanOnStart() );
    }

    @Test
    public void indexMustBeCleanWhenClosedAfterCheckpoint() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong, MutableLong> index = index().build() )
        {
            insert( index, 0, 1 );

            index.checkpoint( unlimited() );
        }

        // WHEN
        MonitorDirty monitorDirty = new MonitorDirty();
        try ( GBPTree<MutableLong, MutableLong> ignored = index().with( monitorDirty ).build() )
        {
        }

        // THEN
        assertTrue( "Expected to be clean on start after close with checkpoint", monitorDirty.cleanOnStart() );
    }

    @Test
    public void indexMustBeDirtyWhenClosedWithChangesSinceLastCheckpoint() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong, MutableLong> index = index().build() )
        {
            insert( index, 0, 1 );

            // no checkpoint
        }

        // WHEN
        MonitorDirty monitorDirty = new MonitorDirty();
        try ( GBPTree<MutableLong, MutableLong> ignored = index().with( monitorDirty ).build() )
        {
        }

        // THEN
        assertFalse( "Expected to be dirty on start after close without checkpoint",
                monitorDirty.cleanOnStart() );
    }

    @Test
    public void indexMustBeDirtyWhenCrashedWithChangesSinceLastCheckpoint() throws Exception
    {
        // GIVEN
        try ( EphemeralFileSystemAbstraction ephemeralFs = new EphemeralFileSystemAbstraction() )
        {
            ephemeralFs.mkdirs( indexFile.getParentFile() );
            PageCache pageCache = pageCacheRule.getPageCache( ephemeralFs );
            EphemeralFileSystemAbstraction snapshot;
            try ( GBPTree<MutableLong, MutableLong> index = index().with( pageCache ).build() )
            {
                insert( index, 0, 1 );

                // WHEN
                // crash
                snapshot = ephemeralFs.snapshot();
            }
            pageCache.close();

            // THEN
            MonitorDirty monitorDirty = new MonitorDirty();
            pageCache = pageCacheRule.getPageCache( snapshot );
            try ( GBPTree<MutableLong, MutableLong> ignored = index().with( pageCache ).with( monitorDirty ).build() )
            {
            }
            assertFalse( "Expected to be dirty on start after crash",
                    monitorDirty.cleanOnStart() );
        }
    }

    @Test
    public void cleanCrashPointersMustTriggerOnDirtyStart() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong, MutableLong> index = index().build() )
        {
            insert( index, 0, 1 );

            // No checkpoint
        }

        // WHEN
        MonitorCleanup monitor = new MonitorCleanup();
        try ( GBPTree<MutableLong, MutableLong> ignored = index().with( monitor ).build() )
        {
            // THEN
            assertTrue( "Expected cleanup to be called when starting on dirty tree", monitor.cleanupCalled() );
        }
    }

    @Test
    public void cleanCrashPointersMustNotTriggerOnCleanStart() throws Exception
    {
        // GIVEN
        try ( GBPTree<MutableLong, MutableLong> index = index().build() )
        {
            insert( index, 0, 1 );

            index.checkpoint( IOLimiter.unlimited() );
        }

        // WHEN
        MonitorCleanup monitor = new MonitorCleanup();
        try ( GBPTree<MutableLong, MutableLong> ignored = index().with( monitor ).build() )
        {
            // THEN
            assertFalse( "Expected cleanup not to be called when starting on clean tree", monitor.cleanupCalled() );
        }
    }

    /* TreeState has outdated root */

    @Test
    public void shouldThrowIfTreeStatePointToRootWithValidSuccessor() throws Exception
    {
        // GIVEN
        int pageSize = 256;
        try ( PageCache specificPageCache = createPageCache( pageSize ) )
        {
            try ( GBPTree<MutableLong,MutableLong> ignore = index().with( specificPageCache ).build() )
            {
            }

            // a tree state pointing to root with valid successor
            try ( PagedFile pagedFile = specificPageCache.map( indexFile, specificPageCache.pageSize() );
                  PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
            {
                Pair<TreeState,TreeState> treeStates =
                        TreeStatePair.readStatePages( cursor, IdSpace.STATE_PAGE_A, IdSpace.STATE_PAGE_B );
                TreeState newestState = TreeStatePair.selectNewestValidState( treeStates );
                long rootId = newestState.rootId();
                long stableGeneration = newestState.stableGeneration();
                long unstableGeneration = newestState.unstableGeneration();

                TreeNode.goTo( cursor, "root", rootId );
                TreeNode.setSuccessor( cursor, 42, stableGeneration + 1, unstableGeneration + 1 );
            }

            // WHEN
            try ( GBPTree<MutableLong,MutableLong> index = index().with( specificPageCache ).build() )
            {
                try ( Writer<MutableLong, MutableLong> ignored = index.writer() )
                {
                    fail( "Expected to throw because root pointed to by tree state should have a valid successor." );
                }
            }
            catch ( TreeInconsistencyException e )
            {
                assertThat( e.getMessage(), containsString( PointerChecking.WRITER_TRAVERSE_OLD_STATE_MESSAGE ) );
            }
        }
    }

    /* IO failure on close */

    @Test
    public void mustRetryCloseIfFailure() throws Exception
    {
        // GIVEN
        AtomicBoolean throwOnNext = new AtomicBoolean();
        IOException exception = new IOException( "My failure" );
        PageCache pageCache = pageCacheThatThrowExceptionWhenToldTo( exception, throwOnNext );
        try ( GBPTree<MutableLong, MutableLong> ignored = index().with( pageCache ).build() )
        {
            // WHEN
            throwOnNext.set( true );
        }
    }

    private class ControlledRecoveryCleanupWorkCollector extends LifecycleAdapter
            implements RecoveryCleanupWorkCollector
    {
        Queue<CleanupJob> jobs = new LinkedList<>();

        @Override
        public void start() throws Throwable
        {
            CleanupJob job;
            while ( (job = jobs.poll()) != null )
            {
                job.run();
            }
        }

        @Override
        public void add( CleanupJob job )
        {
            jobs.add( job );
        }
    }

    private PageCache pageCacheThatThrowExceptionWhenToldTo( final IOException e, final AtomicBoolean throwOnNextIO )
    {
        return new DelegatingPageCache( createPageCache( 256 ) )
        {
            @Override
            public PagedFile map( File file, int pageSize, OpenOption... openOptions ) throws IOException
            {
                return new DelegatingPagedFile( super.map( file, pageSize, openOptions ) )
                {
                    @Override
                    public PageCursor io( long pageId, int pf_flags ) throws IOException
                    {
                        maybeThrow();
                        return super.io( pageId, pf_flags );
                    }

                    @Override
                    public void flushAndForce( IOLimiter limiter ) throws IOException
                    {
                        maybeThrow();
                        super.flushAndForce( limiter );
                    }

                    private void maybeThrow() throws IOException
                    {
                        if ( throwOnNextIO.get() )
                        {
                            throwOnNextIO.set( false );
                            assert e != null;
                            throw e;
                        }
                    }
                };
            }
        };
    }

    private void insert( GBPTree<MutableLong,MutableLong> index, long key, long value ) throws IOException
    {
        try ( Writer<MutableLong, MutableLong> writer = index.writer() )
        {
            writer.put( new MutableLong( key ), new MutableLong( value ) );
        }
    }

    private void shouldWait( Future<?> future )throws InterruptedException, ExecutionException
    {
        try
        {
            future.get( 200, TimeUnit.MILLISECONDS );
            fail( "Expected timeout" );
        }
        catch ( TimeoutException e )
        {
            // good
        }
    }

    private PageCache createPageCache( int pageSize )
    {
        return pageCacheRule.getPageCache( fs.get(), config().withPageSize( pageSize ) );
    }

    private GBPTreeBuilder index()
    {
        return new GBPTreeBuilder();
    }

    private class GBPTreeBuilder
    {
        private int pageCachePageSize = 256;
        private int tentativePageSize;
        private Monitor monitor = NO_MONITOR;
        private Header.Reader headerReader = NO_HEADER;
        private Layout<MutableLong,MutableLong> layout = GBPTreeTest.layout;
        private PageCache specificPageCache;
        private RecoveryCleanupWorkCollector recoveryCleanupWorkCollector = RecoveryCleanupWorkCollector.IMMEDIATE;

        private GBPTreeBuilder withPageCachePageSize( int pageSize )
        {
            this.pageCachePageSize = pageSize;
            return this;
        }

        private GBPTreeBuilder withIndexPageSize( int tentativePageSize )
        {
            this.tentativePageSize = tentativePageSize;
            return this;
        }

        private GBPTreeBuilder with( GBPTree.Monitor monitor )
        {
            this.monitor = monitor;
            return this;
        }

        private GBPTreeBuilder with( Header.Reader headerReader )
        {
            this.headerReader = headerReader;
            return this;
        }

        private GBPTreeBuilder with( Layout<MutableLong,MutableLong> layout )
        {
            this.layout = layout;
            return this;
        }

        private GBPTreeBuilder with( PageCache pageCache )
        {
            this.specificPageCache = pageCache;
            return this;
        }

        private GBPTreeBuilder with( RecoveryCleanupWorkCollector recoveryCleanupWorkCollector )
        {
            this.recoveryCleanupWorkCollector = recoveryCleanupWorkCollector;
            return this;
        }

        private GBPTree<MutableLong,MutableLong> build() throws IOException
        {
            PageCache pageCacheToUse;
            if ( specificPageCache == null )
            {
                if ( pageCache != null )
                {
                    pageCache.close();
                }
                pageCache = createPageCache( pageCachePageSize );
                pageCacheToUse = pageCache;
            }
            else
            {
                pageCacheToUse = specificPageCache;
            }

            return new GBPTree<>( pageCacheToUse, indexFile, layout, tentativePageSize, monitor, headerReader,
                    recoveryCleanupWorkCollector );
        }
    }

    private static class CleanJobControlledMonitor extends Monitor.Adaptor
    {
        private final Barrier.Control barrier = new Barrier.Control();

        @Override
        public void cleanupFinished( long numberOfPagesVisited, long numberOfCleanedCrashPointers, long durationMillis )
        {
            barrier.reached();
        }
    }

    private static class CheckpointControlledMonitor extends Monitor.Adaptor
    {
        private final Barrier.Control barrier = new Barrier.Control();
        private volatile boolean enabled;

        @Override
        public void checkpointCompleted()
        {
            if ( enabled )
            {
                barrier.reached();
            }
        }
    }

    private static class CheckpointCounter extends Monitor.Adaptor
    {
        private int count;

        @Override
        public void checkpointCompleted()
        {
            count++;
        }

        public void reset()
        {
            count = 0;
        }

        public int count()
        {
            return count;
        }
    }

    private void setFormatVersion( int pageSize, int formatVersion ) throws IOException
    {
        try ( PagedFile pagedFile = pageCache.map( indexFile, pageSize );
              PageCursor cursor = pagedFile.io( IdSpace.META_PAGE_ID, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next() );
            cursor.putInt( formatVersion );
        }
    }

    private static class MonitorDirty extends Monitor.Adaptor
    {
        private boolean called;
        private boolean cleanOnStart;

        @Override
        public void startupState( boolean clean )
        {
            if ( called )
            {
                throw new IllegalStateException( "State has already been set. Can't set it again." );
            }
            called = true;
            cleanOnStart = clean;
        }

        boolean cleanOnStart()
        {
            if ( !called )
            {
                throw new IllegalStateException( "State has not been set" );
            }
            return cleanOnStart;
        }
    }

    private static class MonitorCleanup extends Monitor.Adaptor
    {
        private boolean cleanupCalled;

        @Override
        public void cleanupFinished( long numberOfPagesVisited, long numberOfCleanedCrashPointers, long durationMillis )
        {
            cleanupCalled = true;
        }

        boolean cleanupCalled()
        {
            return cleanupCalled;
        }
    }
}
