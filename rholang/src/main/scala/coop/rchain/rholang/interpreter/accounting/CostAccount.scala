package coop.rchain.rholang.interpreter.accounting

import cats.Monoid
import coop.rchain.models.PCost

case class CostAccount(idx: Int, cost: Cost) {
  def charge(value: Cost): CostAccount =
    copy(idx = this.idx + 1, cost = this.cost + value)
  def charge(other: CostAccount): CostAccount =
    copy(idx = this.idx + other.idx, cost = this.cost + other.cost)
  def +(cost: Cost): CostAccount        = charge(cost)
  def +(cost: CostAccount): CostAccount = charge(cost)
}

object CostAccount {
  def apply(value: Long): CostAccount  = CostAccount(0, Cost(value))
  def toProto(c: CostAccount): PCost   = PCost(c.idx, c.cost.value)
  def fromProto(c: PCost): CostAccount = CostAccount(c.iterations, Cost(c.cost))
  def zero: CostAccount                = CostAccount(0, Cost(0))

  implicit val monoidCostAccount: Monoid[CostAccount] = new Monoid[CostAccount] {
    override def empty: CostAccount = CostAccount.zero

    override def combine(x: CostAccount, y: CostAccount): CostAccount = x.charge(y)
  }
}
