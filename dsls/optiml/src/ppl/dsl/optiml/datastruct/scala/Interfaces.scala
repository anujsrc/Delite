package ppl.dsl.optiml.datastruct.scala

/**
 * Delite
 */

trait DeliteOpMapReduce[@specialized A, @specialized R] {
    /**
   * The input collection
   */
  def in: DeliteCollection[A]

  /**
   * Map: A => R
   */
  def map(elem: A): R

  /**
   *  Reduce: (R,R) => R
   */
  def reduce(tup: (R, R)): R

  /**
   * default implementation of map-reduce is simply to compose the map and reduce functions
   * A subclass can override to fuse the implementations
   */
  def mapreduce(acc: R, elem: A): R = reduce((acc, map(elem)))

}

trait DeliteCollection[@specialized T] {
  def size: Int
  def apply(idx: Int): T
  def update[A <: T](idx: Int, x: A)
}

/**
 * Vector
 */

trait Vector[@specialized T] extends ppl.delite.framework.DeliteCollection[T] {
  // fields required on real underlying data structure impl
  def length : Int
  def is_row : Boolean
  def apply(n: Int) : T
  def update[A <: T](index: Int, x: A)

  // DeliteCollection
  def size = length
}

trait NilVector[@specialized T] extends Vector[T]

trait VectorView[@specialized T] extends Vector[T]

/**
 * Matrix
 */
trait Matrix[@specialized T] {
  // fields required on real underlying data structure impl
  def numRows: Int
  def numCols: Int
  def size: Int

  def apply(i: Int) : VectorView[T]
  def apply(i: Int, j: Int) : T
  def update[A <: T](row: Int, col: Int, x: A)
  def vview(start: Int, stride: Int, length: Int, is_row: Boolean) : VectorView[T]
  def insertRow[A <: T](pos: Int, x: Vector[A]): Matrix[T]
}


/**
 * Ref
 */

case class Ref[@specialized T](v: T) {
  private var _v = v

  def get = _v
  def set(v: T) = _v = v
}
