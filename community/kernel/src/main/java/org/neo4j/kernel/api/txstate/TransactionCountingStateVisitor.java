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
package org.neo4j.kernel.api.txstate;

import java.util.Set;

import org.neo4j.collection.primitive.PrimitiveIntCollection;
import org.neo4j.collection.primitive.PrimitiveIntSet;
import org.neo4j.collection.primitive.PrimitiveIntVisitor;
import org.neo4j.cursor.Cursor;
import org.neo4j.kernel.api.exceptions.schema.ConstraintValidationException;
import org.neo4j.kernel.impl.api.CountsRecordState;
import org.neo4j.storageengine.api.NodeItem;
import org.neo4j.storageengine.api.RelationshipItem;
import org.neo4j.storageengine.api.StoreReadLayer;
import org.neo4j.storageengine.api.txstate.ReadableTransactionState;
import org.neo4j.storageengine.api.txstate.TxStateVisitor;

import static org.neo4j.kernel.api.ReadOperations.ANY_LABEL;
import static org.neo4j.kernel.api.ReadOperations.ANY_RELATIONSHIP_TYPE;

public class TransactionCountingStateVisitor extends TxStateVisitor.Delegator
{
    private final StoreReadLayer storeLayer;
    private final CountsRecordState counts;
    private final ReadableTransactionState txState;

    public TransactionCountingStateVisitor( TxStateVisitor next, StoreReadLayer storeLayer,
            ReadableTransactionState txState, CountsRecordState counts )
    {
        super( next );
        this.storeLayer = storeLayer;
        this.txState = txState;
        this.counts = counts;
    }

    @Override
    public void visitCreatedNode( long id )
    {
        counts.incrementNodeCount( ANY_LABEL, 1 );
        super.visitCreatedNode( id );
    }

    @Override
    public void visitDeletedNode( long id )
    {
        counts.incrementNodeCount( ANY_LABEL, -1 );
        storeLayer.nodeGetSingleCursor( id, ReadableTransactionState.EMPTY )
                .forAll( this::decrementCountForLabelsAndRelationships );
        super.visitDeletedNode( id );
    }

    private void decrementCountForLabelsAndRelationships( NodeItem node )
    {
        PrimitiveIntSet labelIds = node.labels();
        labelIds.visitKeys( labelId ->
        {
            counts.incrementNodeCount( labelId, -1 );
            return false;
        } );

        storeLayer.degrees( node,
                ( type, out, in ) -> updateRelationshipsCountsFromDegrees( labelIds, type, -out, -in ) );
    }

    @Override
    public void visitCreatedRelationship( long id, int type, long startNode, long endNode )
            throws ConstraintValidationException
    {
        updateRelationshipCount( startNode, type, endNode, 1 );
        super.visitCreatedRelationship( id, type, startNode, endNode );
    }

    @Override
    public void visitDeletedRelationship( long id )
    {
        try ( Cursor<RelationshipItem> cursor = storeLayer.relationshipGetSingleCursor( id, ReadableTransactionState.EMPTY ) )
        {
            if ( !cursor.next() )
            {
                throw new IllegalStateException( "Relationship being deleted should exist along with its nodes." );
            }

            RelationshipItem relationship = cursor.get();
            updateRelationshipCount( relationship.startNode(), relationship.type(), relationship.endNode(), -1 );
        }
        super.visitDeletedRelationship( id );
    }

    @Override
    public void visitNodeLabelChanges( long id, final Set<Integer> added, final Set<Integer> removed )
            throws ConstraintValidationException
    {
        // update counts
        if ( !(added.isEmpty() && removed.isEmpty()) )
        {
            for ( Integer label : added )
            {
                counts.incrementNodeCount( label, 1 );
            }
            for ( Integer label : removed )
            {
                counts.incrementNodeCount( label, -1 );
            }
            // get the relationship counts from *before* this transaction,
            // the relationship changes will compensate for what happens during the transaction
            storeLayer.nodeGetSingleCursor( id, ReadableTransactionState.EMPTY )
                    .forAll( node -> storeLayer.degrees( node, ( type, out, in ) ->
                    {
                        added.forEach( label -> updateRelationshipsCountsFromDegrees( type, label, out, in ) );
                        removed.forEach( label -> updateRelationshipsCountsFromDegrees( type, label, -out, -in ) );
                    } ) );
        }
        super.visitNodeLabelChanges( id, added, removed );
    }

    private void updateRelationshipsCountsFromDegrees( PrimitiveIntCollection labels, int type, long out, long in )
    {
        labels.visitKeys( label -> updateRelationshipsCountsFromDegrees( type, label, out, in ) );
    }

    private boolean updateRelationshipsCountsFromDegrees( int type, int label, long out, long in )
    {
        // untyped
        counts.incrementRelationshipCount( label, ANY_RELATIONSHIP_TYPE, ANY_LABEL, out );
        counts.incrementRelationshipCount( ANY_LABEL, ANY_RELATIONSHIP_TYPE, label, in );
        // typed
        counts.incrementRelationshipCount( label, type, ANY_LABEL, out );
        counts.incrementRelationshipCount( ANY_LABEL, type, label, in );
        return false;
    }

    private void updateRelationshipCount( long startNode, int type, long endNode, int delta )
    {
        updateRelationshipsCountsFromDegrees( type, ANY_LABEL, delta, 0 );
        visitLabels( startNode, ( labelId ) -> updateRelationshipsCountsFromDegrees( type, labelId, delta, 0) );
        visitLabels( endNode, ( labelId ) -> updateRelationshipsCountsFromDegrees( type, labelId, 0, delta ) );
    }

    private void visitLabels( long nodeId, PrimitiveIntVisitor<RuntimeException> visitor )
    {
        storeLayer.nodeGetSingleCursor( nodeId, txState ).forAll( node -> node.labels().visitKeys( visitor ) );
    }
}
