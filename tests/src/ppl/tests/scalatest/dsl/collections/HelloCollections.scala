package ppl.tests.scalatest.dsl.collections



import ppl.delite.framework.DeliteApplication
import ppl.delite.framework.collections._
import ppl.dsl.optiml.{OptiMLApplicationRunner, OptiMLApplication}
import ppl.tests.scalatest._



trait BasicExamples extends CollectionsApplication with DeliteTestModule {
  def main() {
    implicit val collector = ArrayBuffer[Boolean]()
    
    val xs = ArraySeq.range(6).map(x => x)
    println(xs)
    collect(xs.size == 6)
    
    val ys = ArraySeq.range(6).filter(x => x % 2 == 0)
    println(ys)
    collect(ys.size == 3)
    
    val ms = HashMap1.range(6).map(x => x)
    println(ms)
    collect(ms.size == 6)
    
    val mg = ArraySeq.range(6).groupBy(x => x % 2)
    println(mg)
    collect(mg.size == 2)
    
    val zs = ArraySeq.range(6).flatMap(x => ArraySeq.range(x))
    println(zs)
    collect(zs.size == 15)
    
    mkReport
  }
}


object BasicExamplesRunner extends CollectionsApplicationRunner with BasicExamples with DeliteTestRunner


trait HashMap1Map extends CollectionsApplication with DeliteTestModule {
  def main() {
    implicit val collector = ArrayBuffer[Boolean]()
    
    val sz = 60
    val ms = HashMap1.range(sz)
    val ms2 = ms.map(kv => kv)
    //println(ms2)
    collect(ms2.size == sz)
    for (i <- 0 until sz) collect(ms2.get(i) == i)
    
    //println(HashMap1.range(0))
    val hm0 = HashMap1.range(0).map(kv => kv)
    collect(hm0.size == 0)
    
    val hm1 = HashMap1.range(1).map(kv => kv)
    collect(hm1.size == 1)
    for (k <- 0 until 1) collect(hm1.get(k) == k)
    
    val hm2 = HashMap1.range(2).map(kv => kv)
    collect(hm2.size == 2)
    for (k <- 0 until 2) collect(hm2.get(k) == k)
    
    val hm5 = HashMap1.range(5).map(kv => kv)
    collect(hm5.size == 5)
    for (k <- 0 until 5) collect(hm5.get(k) == k)
    
    val hm10 = HashMap1.range(10).map(kv => kv)
    collect(hm10.size == 10)
    for (k <- 0 until 10) collect(hm10.get(k) == k)
    
    val hm15 = HashMap1.range(15).map(kv => kv)
    collect(hm15.size == 15)
    for (k <- 0 until 15) collect(hm15.get(k) == k)
    
    val hm150 = HashMap1.range(150).map(kv => kv)
    collect(hm150.size == 150)
    for (k <- 0 until 150) collect(hm150.get(k) == k)
    
    val hm256 = HashMap1.range(256).map(kv => kv)
    collect(hm256.size == 256)
    for (k <- 0 until 256) collect(hm256.get(k) == k)
    
    val hm300 = HashMap1.range(300).map(kv => kv)
    collect(hm300.size == 300)
    for (k <- 0 until 300) collect(hm300.get(k) == k)
    
    val hm15000 = HashMap1.range(15000).map(kv => kv)
    collect(hm15000.size == 15000)
    for (k <- 0 until 15000) collect(hm15000.get(k) == k)
    
    mkReport
  }
}


object HashMap1MapRunner extends CollectionsApplicationRunner with HashMap1Map with DeliteTestRunner


trait ArraySeqGroupBy extends CollectionsApplication with DeliteTestModule {
  def main() {
    implicit val collector = ArrayBuffer[Boolean]()
    
    val sz = 6
    val mod = 2
    val mg = ArraySeq.range(sz).groupBy(x => x % mod)
    collect(mg.size == mod)
    for (k <- 0 until mod) {
      val b = mg.get(k)
      dcToDcOps(b).size
      collect(b.size == (sz / mod))
      for (i <- 0 until (sz / mod)) collect(b(i) == (mod * i + k))
    }
    
    val sz1 = 1000
    val mod1 = 2
    val mg1 = ArraySeq.range(sz1).groupBy(x => x % mod1)
    collect(mg1.size == mod1)
    for (k <- 0 until mod1) {
      val b = mg1.get(k)
      dcToDcOps(b).size
      collect(b.size == (sz1 / mod1))
      for (i <- 0 until (sz1 / mod1)) collect(b(i) == (mod1 * i + k))
    }
    
    val sz2 = 1000
    val mod2 = 100
    val mg2 = ArraySeq.range(sz2).groupBy(x => x % mod2)
    collect(mg2.size == mod2)
    for (k <- 0 until mod2) {
      val b = mg2.get(k)
      dcToDcOps(b).size
      collect(b.size == (sz2 / mod2))
      for (i <- 0 until (sz2 / mod2)) collect(b(i) == (mod2 * i + k))
    }
    
    mkReport
  }
}


object ArraySeqGroupByRunner extends CollectionsApplicationRunner with ArraySeqGroupBy with DeliteTestRunner


class HelloCollectionsOpsSuite extends DeliteSuite {
  def testBasicExamples() { compileAndTest(BasicExamplesRunner) }
  def testHashMap1Map() { compileAndTest(HashMap1MapRunner) }
  def testArraySeqGroupBy() { compileAndTest(ArraySeqGroupByRunner) }
}




