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
package org.neo4j.cypher.internal.frontend.v3_3.ast.rewriters

import org.neo4j.cypher.internal.frontend.v3_3
import org.neo4j.cypher.internal.frontend.v3_3.ast._
import org.neo4j.cypher.internal.frontend.v3_3.{Rewriter, bottomUp}

case object foldConstants extends Rewriter {
  def apply(that: AnyRef): AnyRef =
  try {
    instance.apply(that)
  } catch {
    case e: java.lang.ArithmeticException => throw new v3_3.ArithmeticException(e.getMessage, e)
  }
  private val instance: Rewriter = bottomUp(Rewriter.lift {
    case e@Add(lhs: SignedIntegerLiteral, rhs: SignedIntegerLiteral) =>
      SignedDecimalIntegerLiteral((lhs.value + rhs.value).toString)(e.position)
    case e@Add(lhs: DecimalDoubleLiteral, rhs: SignedIntegerLiteral) =>
      DecimalDoubleLiteral((lhs.value + rhs.value).toString)(e.position)
    case e@Add(lhs: SignedIntegerLiteral, rhs: DecimalDoubleLiteral) =>
      DecimalDoubleLiteral((lhs.value + rhs.value).toString)(e.position)
    case e@Add(lhs: DecimalDoubleLiteral, rhs: DecimalDoubleLiteral) =>
      DecimalDoubleLiteral((lhs.value + rhs.value).toString)(e.position)

    case e@Subtract(lhs: SignedIntegerLiteral, rhs: SignedIntegerLiteral) =>
      SignedDecimalIntegerLiteral((lhs.value - rhs.value).toString)(e.position)
    case e@Subtract(lhs: DecimalDoubleLiteral, rhs: SignedIntegerLiteral) =>
      DecimalDoubleLiteral((lhs.value - rhs.value).toString)(e.position)
    case e@Subtract(lhs: SignedIntegerLiteral, rhs: DecimalDoubleLiteral) =>
      DecimalDoubleLiteral((lhs.value - rhs.value).toString)(e.position)
    case e@Subtract(lhs: DecimalDoubleLiteral, rhs: DecimalDoubleLiteral) =>
      DecimalDoubleLiteral((lhs.value - rhs.value).toString)(e.position)

    case e@Multiply(lhs: SignedIntegerLiteral, rhs: SignedIntegerLiteral) =>
      SignedDecimalIntegerLiteral((lhs.value * rhs.value).toString)(e.position)
    case e@Multiply(lhs: DecimalDoubleLiteral, rhs: SignedIntegerLiteral) =>
      DecimalDoubleLiteral((lhs.value * rhs.value).toString)(e.position)
    case e@Multiply(lhs: SignedIntegerLiteral, rhs: DecimalDoubleLiteral) =>
      DecimalDoubleLiteral((lhs.value * rhs.value).toString)(e.position)
    case e@Multiply(lhs: DecimalDoubleLiteral, rhs: DecimalDoubleLiteral) =>
      DecimalDoubleLiteral((lhs.value * rhs.value).toString)(e.position)

    case e@Multiply(lhs: NumberLiteral, rhs: NumberLiteral) =>
      e
    case e@Multiply(lhs: NumberLiteral, rhs) =>
      Multiply(rhs, lhs)(e.position).rewrite(instance)
    case e@Multiply(lhs@Multiply(innerLhs, innerRhs: NumberLiteral), rhs: NumberLiteral) =>
      Multiply(Multiply(innerRhs, rhs)(lhs.position), innerLhs)(e.position).rewrite(instance)
    case e@Multiply(lhs@Multiply(innerLhs: NumberLiteral, innerRhs), rhs: NumberLiteral) =>
      Multiply(Multiply(innerLhs, rhs)(lhs.position), innerRhs)(e.position).rewrite(instance)

    case e@Divide(lhs: SignedIntegerLiteral, rhs: SignedIntegerLiteral) =>
      SignedDecimalIntegerLiteral((lhs.value / rhs.value).toString)(e.position)
    case e@Divide(lhs: DecimalDoubleLiteral, rhs: SignedIntegerLiteral) =>
      DecimalDoubleLiteral((lhs.value / rhs.value).toString)(e.position)
    case e@Divide(lhs: SignedIntegerLiteral, rhs: DecimalDoubleLiteral) =>
      DecimalDoubleLiteral((lhs.value / rhs.value).toString)(e.position)
    case e@Divide(lhs: DecimalDoubleLiteral, rhs: DecimalDoubleLiteral) =>
      DecimalDoubleLiteral((lhs.value / rhs.value).toString)(e.position)

    case e@Modulo(lhs: SignedIntegerLiteral, rhs: SignedIntegerLiteral) =>
      SignedDecimalIntegerLiteral((lhs.value % rhs.value).toString)(e.position)
    case e@Modulo(lhs: DecimalDoubleLiteral, rhs: SignedIntegerLiteral) =>
      DecimalDoubleLiteral((lhs.value % rhs.value).toString)(e.position)
    case e@Modulo(lhs: SignedIntegerLiteral, rhs: DecimalDoubleLiteral) =>
      DecimalDoubleLiteral((lhs.value % rhs.value).toString)(e.position)
    case e@Modulo(lhs: DecimalDoubleLiteral, rhs: DecimalDoubleLiteral) =>
      DecimalDoubleLiteral((lhs.value % rhs.value).toString)(e.position)

    case e@Pow(lhs: SignedIntegerLiteral, rhs: SignedIntegerLiteral) =>
      DecimalDoubleLiteral(Math.pow(lhs.value.toDouble, rhs.value.toDouble).toString)(e.position)
    case e@Pow(lhs: DecimalDoubleLiteral, rhs: SignedIntegerLiteral) =>
      DecimalDoubleLiteral(Math.pow(lhs.value, rhs.value.toDouble).toString)(e.position)
    case e@Pow(lhs: SignedIntegerLiteral, rhs: DecimalDoubleLiteral) =>
      DecimalDoubleLiteral(Math.pow(lhs.value.toDouble, rhs.value).toString)(e.position)
    case e@Pow(lhs: DecimalDoubleLiteral, rhs: DecimalDoubleLiteral) =>
      DecimalDoubleLiteral(Math.pow(lhs.value, rhs.value).toString)(e.position)

    case e: UnaryAdd =>
      e.rhs

    case e@UnarySubtract(rhs: SignedIntegerLiteral) =>
      SignedDecimalIntegerLiteral((-rhs.value).toString)(e.position)
    case e: UnarySubtract =>
      Subtract(SignedDecimalIntegerLiteral("0")(e.position), e.rhs)(e.position)

    case e@Equals(lhs: IntegerLiteral, rhs: IntegerLiteral) => asAst(lhs.value == rhs.value, e)
    case e@Equals(lhs: DoubleLiteral, rhs: DoubleLiteral) => asAst(lhs.value == rhs.value, e)
    case e@Equals(lhs: IntegerLiteral, rhs: DoubleLiteral) => asAst(lhs.value.doubleValue() == rhs.value, e)
    case e@Equals(lhs: DoubleLiteral, rhs: IntegerLiteral) => asAst(lhs.value == rhs.value.doubleValue(), e)

    case e@LessThan(lhs: IntegerLiteral, rhs: IntegerLiteral) => asAst(lhs.value < rhs.value, e)
    case e@LessThan(lhs: DoubleLiteral, rhs: DoubleLiteral) => asAst(lhs.value < rhs.value, e)
    case e@LessThan(lhs: IntegerLiteral, rhs: DoubleLiteral) => asAst(lhs.value.doubleValue() < rhs.value, e)
    case e@LessThan(lhs: DoubleLiteral, rhs: IntegerLiteral) => asAst(lhs.value < rhs.value.doubleValue(), e)

    case e@GreaterThan(lhs: IntegerLiteral, rhs: IntegerLiteral) => asAst(lhs.value > rhs.value, e)
    case e@GreaterThan(lhs: DoubleLiteral, rhs: DoubleLiteral) => asAst(lhs.value > rhs.value, e)
    case e@GreaterThan(lhs: IntegerLiteral, rhs: DoubleLiteral) => asAst(lhs.value.doubleValue() > rhs.value, e)
    case e@GreaterThan(lhs: DoubleLiteral, rhs: IntegerLiteral) => asAst(lhs.value > rhs.value.doubleValue(), e)
  })

  private def asAst(b: Boolean, e: Expression) = if (b) True()(e.position) else False()(e.position)
}
