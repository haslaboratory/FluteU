//package mboom.core.frontend
//
//import chisel3._
//import chiseltest._
//import chisel3.experimental.VecLiterals._
//import org.scalatest.freespec.AnyFreeSpec
//import chiseltest.ChiselScalatestTester
//import org.scalatest.matchers.should.Matchers
//import mboom.config.CPUConfig._
//import mboom.util.BitMode.fromIntToBitModeLong
//import firrtl.options.TargetDirAnnotation
//import chisel3.stage.ChiselGeneratorAnnotation
//import treadle.TreadleTester
//import chisel3.stage.ChiselStage
//import mboom.FluteTop
//import mboom.cache.top.{ICache, ICacheForTestFetch}
//import mboom.core.backend.commit.BranchCommit
//
//class FetchTest extends AnyFreeSpec with Matchers with ChiselScalatestTester {
//  "Fetch1: base" in {
//    println("=================== Fetch1: base ===================")
//
//    val firrtlAnno = (new ChiselStage).execute(
//      Array(),
//      Seq(
//        TargetDirAnnotation("target"),
//        ChiselGeneratorAnnotation(() => new FetchTestTop("test_data/sort_axi.in"))
//      )
//    )
//
//    val t     = TreadleTester(firrtlAnno)
//    val poke  = t.poke _
//    val peek  = t.peek _
//    var clock = 0
//    def step(n: Int = 1) = {
//      t.step(n)
//      clock += n
//      println(s">>>>>>>>>>>>>>>>>> Total clock steped: ${clock} ")
//    }
//    def displayReadPort() = {
//      for (i <- 0 until 2) {
//        val instruction = peek(s"io_withDecode_ibufferEntries_${i}_bits_inst")
//        val address     = peek(s"io_withDecode_ibufferEntries_${i}_bits_addr")
//        val valid       = peek(s"io_withDecode_ibufferEntries_${i}_valid")
//        println(s"inst #$i: ${"%08x".format(instruction)}")
//        println(s"addr #$i: ${"%08x".format(address)}")
//        println(s"valid #$i: ${valid}")
//      }
//      // println("state: " + peek(s"fetch.state"))
//    }
//
//    step()
//    displayReadPort()
//
//    for (j <- 0 until 8) {
//      for (i <- 0 until 2) {
//        poke(s"io_withDecode_ibufferEntries_${i}_ready", 1)
//      }
//      step(1)
//      for (i <- 0 until 2) {
//        poke(s"io_withDecode_ibufferEntries_${i}_ready", 0)
//      }
//      displayReadPort()
//    }
//
////    poke(s"io_feedbackFromExec_branchAddr_valid", 1)
////    poke(s"io_feedbackFromExec_branchAddr_bits", 0x4c)
////    step(1)
////    poke(s"io_feedbackFromExec_branchAddr_valid", 0)
////    poke(s"io_feedbackFromExec_branchAddr_bits", 0)
//
//    displayReadPort()
//
//    for (j <- 0 until 8) {
//      for (i <- 0 until 2) {
//        poke(s"io_withDecode_ibufferEntries_${i}_ready", 1)
//      }
//      step(1)
//      for (i <- 0 until 2) {
//        poke(s"io_withDecode_ibufferEntries_${i}_ready", 0)
//      }
//      displayReadPort()
//    }
//
//    step(1)
//
//    displayReadPort()
//
//    t.report()
//  }
//
////  "beq bne" in {
////    println("=================== beq bne ===================")
////
////    val firrtlAnno = (new ChiselStage).execute(
////      Array(),
////      Seq(
////        TargetDirAnnotation("target"),
////        ChiselGeneratorAnnotation(() => new FetchTestTop("beq_bne"))
////      )
////    )
////
////    val t     = TreadleTester(firrtlAnno)
////    val poke  = t.poke _
////    val peek  = t.peek _
////    var clock = 0
////    def step(n: Int = 1) = {
////      t.step(n)
////      clock += n
////      println(s">>>>>>>>>>>>>>>>>> Total clock steped: ${clock} ")
////    }
////    def displayReadPort() = {
////      for (i <- 0 until 2) {
////        val instruction = peek(s"io_withDecode_ibufferEntries_${i}_bits_inst")
////        val address     = peek(s"io_withDecode_ibufferEntries_${i}_bits_addr")
////        val valid       = peek(s"io_withDecode_ibufferEntries_${i}_valid")
////        println(s"inst #$i: ${"%08x".format(instruction)}")
////        println(s"addr #$i: ${"%08x".format(address)}")
////        println(s"valid #$i: ${valid}")
////      }
////      println("state: " + peek(s"fetch.state"))
////    }
////
////    step()
////    displayReadPort()
////
////    for (i <- 0 until 2) {
////      poke(s"io_withDecode_ibufferEntries_${i}_ready", 1)
////    }
////    step()
////    for (i <- 0 until 2) {
////      poke(s"io_withDecode_ibufferEntries_${i}_ready", 0)
////    }
////    displayReadPort()
////
////    for (i <- 0 until 2) {
////      poke(s"io_withDecode_ibufferEntries_${i}_ready", 1)
////    }
////    step()
////    for (i <- 0 until 2) {
////      poke(s"io_withDecode_ibufferEntries_${i}_ready", 0)
////    }
////    displayReadPort()
////
////    poke(s"io_feedbackFromExec_branchAddr_valid", 1)
////    poke(s"io_feedbackFromExec_branchAddr_bits", 0x14)
////    step(1)
////    poke(s"io_feedbackFromExec_branchAddr_valid", 0)
////    poke(s"io_feedbackFromExec_branchAddr_bits", 0)
////    displayReadPort()
////
////    for (i <- 0 until 2) {
////      poke(s"io_withDecode_ibufferEntries_${i}_ready", 1)
////    }
////    step()
////    for (i <- 0 until 2) {
////      poke(s"io_withDecode_ibufferEntries_${i}_ready", 0)
////    }
////    displayReadPort()
////
////    step()
////
////    displayReadPort()
////
////    for (i <- 0 until 2) {
////      poke(s"io_withDecode_ibufferEntries_${i}_ready", 1)
////    }
////    step()
////    for (i <- 0 until 2) {
////      poke(s"io_withDecode_ibufferEntries_${i}_ready", 0)
////    }
////    displayReadPort()
////
////    poke(s"io_feedbackFromExec_branchAddr_valid", 1)
////    poke(s"io_feedbackFromExec_branchAddr_bits", 0x28)
////    step(1)
////    poke(s"io_feedbackFromExec_branchAddr_valid", 0)
////    poke(s"io_feedbackFromExec_branchAddr_bits", 0)
////    displayReadPort()
////
////    step(2)
////
////    displayReadPort()
////
////    poke(s"io_feedbackFromExec_branchAddr_valid", 1)
////    poke(s"io_feedbackFromExec_branchAddr_bits", 0x30)
////    step(1)
////    poke(s"io_feedbackFromExec_branchAddr_valid", 0)
////    poke(s"io_feedbackFromExec_branchAddr_bits", 0)
////    displayReadPort()
////
////    step(2)
////
////    displayReadPort()
////
////    for (i <- 0 until 2) {
////      poke(s"io_withDecode_ibufferEntries_${i}_ready", 1)
////    }
////    step()
////    for (i <- 0 until 2) {
////      poke(s"io_withDecode_ibufferEntries_${i}_ready", 0)
////    }
////    displayReadPort()
////
////    poke(s"io_feedbackFromExec_branchAddr_valid", 1)
////    poke(s"io_feedbackFromExec_branchAddr_bits", 0x44)
////    step(1)
////    poke(s"io_feedbackFromExec_branchAddr_valid", 0)
////    poke(s"io_feedbackFromExec_branchAddr_bits", 0)
////    displayReadPort()
////
////    step(2)
////
////    displayReadPort()
////
////    for (i <- 0 until 2) {
////      poke(s"io_withDecode_ibufferEntries_${i}_ready", 1)
////    }
////    step()
////    for (i <- 0 until 2) {
////      poke(s"io_withDecode_ibufferEntries_${i}_ready", 0)
////    }
////    displayReadPort()
////  }
////
////  "fetch2_j" in {
////    println("=================== Fetch2: J ===================")
////
////    val firrtlAnno = (new ChiselStage).execute(
////      Array(),
////      Seq(
////        TargetDirAnnotation("target"),
////        ChiselGeneratorAnnotation(() => new FetchTestTop("fetch2_j"))
////      )
////    )
////
////    val t     = TreadleTester(firrtlAnno)
////    val poke  = t.poke _
////    val peek  = t.peek _
////    var clock = 0
////    def step(n: Int = 1) = {
////      t.step(n)
////      clock += n
////      println(s">>>>>>>>>>>>>>>>>> Total clock steped: ${clock} ")
////    }
////    def displayReadPort() = {
////      for (i <- 0 until 2) {
////        val instruction = peek(s"io_withDecode_ibufferEntries_${i}_bits_inst")
////        val address     = peek(s"io_withDecode_ibufferEntries_${i}_bits_addr")
////        val valid       = peek(s"io_withDecode_ibufferEntries_${i}_valid")
////        println(s"inst #$i: ${"%08x".format(instruction)}")
////        println(s"addr #$i: ${"%08x".format(address)}")
////        println(s"valid #$i: ${valid}")
////      }
////      println("state: " + peek(s"fetch.state"))
////    }
////    def take(n: Int) = {
////      for (i <- 0 until n) {
////        poke(s"io_withDecode_ibufferEntries_${i}_ready", 1)
////      }
////      step()
////      for (i <- 0 until n) {
////        poke(s"io_withDecode_ibufferEntries_${i}_ready", 0)
////      }
////    }
////
////    step()
////    displayReadPort()
////
////    take(1)
////    displayReadPort()
////
////    take(1)
////    displayReadPort()
////
////    take(2)
////    displayReadPort()
////
////    take(2)
////    displayReadPort()
////
////    step(1)
////    displayReadPort()
////
////    t.report()
////
////  }
//}
//
//class FetchTestTop(file: String = "") extends Module {
//  val io = IO(new Bundle {
//    val withDecode       = new FetchIO
//  })
//  val iCache = Module(new ICacheForTestFetch(s"${file}"))
//  val fetch  = Module(new FetchWithCache)
//  fetch.io.iCache <> iCache.io
//  io.withDecode <> fetch.io.withDecode
//  fetch.io.branchCommit := 0.U.asTypeOf(Input(new BranchCommitWithBPU))
//  fetch.io.cp0 := 0.U.asTypeOf(new FetchWithCP0)
//}
