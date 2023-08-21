package mboom.cache.lru

import chisel3._
import chisel3.util._

class LRUone(numOfSets: Int) {
  val nextSeq = Seq.tabulate(numOfSets + 2)(i => (i + 1).U(log2Ceil(numOfSets + 1).W))
  val indexSeq = Seq.tabulate(numOfSets + 2)(i => i.U(log2Ceil(numOfSets + 1).W))
  val prevSeq = Seq.tabulate(numOfSets + 2)(i => {
    if (i > 0) {
      (i - 1).U(log2Ceil(numOfSets + 1).W)
    } else {
      (0).U(log2Ceil(numOfSets + 1).W)
    }
  })
  val nexts = RegInit(VecInit(nextSeq))
  val indexs = RegInit(VecInit(indexSeq))
  val prevs = RegInit(VecInit(prevSeq))

  def update(index: UInt): Unit = {
    //		printf(p"${index}\n")
    val pre = prevs(index)
    when(pre === 0.U) {

    }.otherwise {
      val next = nexts(index)
      nexts(pre) := next;
      prevs(next) := pre;

      val new_next = nexts(0.U)
      prevs(new_next) := index
      nexts(index) := new_next
      prevs(index) := 0.U
      nexts(0.U) := index
    }

  }

  def getLRU(): UInt = {
    prevs(numOfSets.U)
  }
}

class LRUt1(numOfSets: Int) extends Module {
  val io = IO(new Bundle() {
    val out = Output(UInt(4.W))
    val in = Input(UInt(4.W))
    val valid = Input(Bool())
  })
  val lru = new LRUone(numOfSets)
  when(io.valid) {
    lru.update(io.in)
  }
  io.out := lru.getLRU()
}