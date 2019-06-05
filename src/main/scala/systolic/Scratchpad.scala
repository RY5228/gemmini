package systolic

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp}
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink.{TLEdgeOut, TLIdentityNode}
import freechips.rocketchip.util.InOrderArbiter
import Util._

class DecoupledTLB(entries: Int)(implicit edge: TLEdgeOut, p: Parameters)
    extends CoreModule {

  val lgMaxSize = log2Ceil(coreDataBytes)
  val io = new Bundle {
    val req = Flipped(Decoupled(new TLBReq(lgMaxSize)))
    val resp = Decoupled(new TLBResp)
    val ptw = new TLBPTWIO
  }

  val req = Reg(new TLBReq(lgMaxSize))
  val resp = Reg(new TLBResp)
  val tlb = Module(new TLB(false, lgMaxSize, TLBConfig(entries)))

  val s_idle :: s_tlb_req :: s_tlb_resp :: s_done :: Nil = Enum(4)
  val state = RegInit(s_idle)

  when (io.req.fire()) {
    req := io.req.bits
    state := s_tlb_req
  }

  when (tlb.io.req.fire()) {
    state := s_tlb_resp
  }

  when (state === s_tlb_resp) {
    when (tlb.io.resp.miss) {
      state := s_tlb_req
    } .otherwise {
      resp := tlb.io.resp
      state := s_done
    }
  }

  when (io.resp.fire()) { state := s_idle }

  io.req.ready := state === s_idle

  tlb.io.sfence.valid := false.B
  tlb.io.sfence.bits := DontCare
  tlb.io.kill := false.B
  tlb.io.req.valid := state === s_tlb_req
  tlb.io.req.bits := req

  io.resp.valid := state === s_done
  io.resp.bits := resp

  io.ptw <> tlb.io.ptw
}

class FrontendTLBIO(implicit p: Parameters) extends CoreBundle {
  val lgMaxSize = log2Ceil(coreDataBytes)
  val req = Decoupled(new TLBReq(lgMaxSize))
  val resp = Flipped(Decoupled(new TLBResp))
}

class FrontendTLB(nClients: Int, entries: Int)
                 (implicit edge: TLEdgeOut, p: Parameters) extends CoreModule {
  val io = IO(new Bundle {
    val clients = Flipped(Vec(nClients, new FrontendTLBIO))
    val ptw = new TLBPTWIO
  })

  val lgMaxSize = log2Ceil(coreDataBytes)
  val tlbArb = Module(new InOrderArbiter(
    new TLBReq(lgMaxSize), new TLBResp, nClients))
  val tlb = Module(new DecoupledTLB(entries))
  tlb.io.req <> tlbArb.io.out_req
  tlbArb.io.out_resp <> tlb.io.resp
  io.ptw <> tlb.io.ptw

  tlbArb.io.in_req <> io.clients.map(_.req)
  io.clients.zip(tlbArb.io.in_resp).foreach {
    case (client, arb_resp) => client.resp <> arb_resp
  }
}

class ScratchpadMemRequest(val nBanks: Int, val nRows: Int, val acc_rows: Int)
    (implicit p: Parameters) extends CoreBundle {
  val vaddr = UInt(coreMaxAddrBits.W)

  val spbank = UInt(log2Ceil(nBanks).W)
  val spaddr = UInt(log2Ceil(nRows).W)

  val accaddr = UInt(log2Ceil(acc_rows).W)
  val is_acc = Bool()

  val stride = UInt(xLen.W)
  val len = UInt(16.W) // TODO don't use a magic number for the width here

  val write = Bool()
}

class ScratchpadMemResponse extends Bundle {
  val error = Bool()
}

class ScratchpadMemIO(val nBanks: Int, val nRows: Int, val acc_rows: Int)
    (implicit p: Parameters) extends CoreBundle {
  val req = Decoupled(new ScratchpadMemRequest(nBanks, nRows, acc_rows))
  val resp = Flipped(Decoupled(new ScratchpadMemResponse))
}

class ScratchpadReadIO(val n: Int, val w: Int) extends Bundle {
  val en = Output(Bool())
  val addr = Output(UInt(log2Ceil(n).W))
  val data = Input(UInt(w.W))
}

class ScratchpadWriteIO(val n: Int, val w: Int) extends Bundle {
  val en = Output(Bool())
  val addr = Output(UInt(log2Ceil(n).W))
  val data = Output(UInt(w.W))
}

class ScratchpadBank(n: Int, w: Int, mem_pipeline: Int) extends Module {
  val io = IO(new Bundle {
    val read = Flipped(new ScratchpadReadIO(n, w))
    val write = Flipped(new ScratchpadWriteIO(n, w))
  })

  /*val mem = SyncReadMem(n, UInt(w.W))

  when (io.write.en) { mem.write(io.write.addr, io.write.data) }

  val raddr = ShiftRegister(io.read.addr, mem_pipeline)
  val ren = ShiftRegister(io.read.en, mem_pipeline)
  io.read.data := mem.read(raddr, ren)*/

  val mem = SyncReadMem(n, UInt(w.W))

  when (io.write.en) { mem.write(io.write.addr, io.write.data) }

  val raddr = io.read.addr
  val ren = io.read.en
  io.read.data := ShiftRegister(mem.read(raddr, ren), mem_pipeline)
}

// TODO find a more elegant way to move data into accumulator
// TODO replace the SRAM types with Vec[Vec[inputType]], rather than just simple UInts
// TODO support unaligned accesses, for both multiple and single matrix loads
// TODO scratchpad is currently broken when one row is larger than dataBits. The requests arrive out-of-order, meaning that half of one row might arrive after the first have of another row. Some kind of re-ordering buffer may be needed
class Scratchpad[T <: Data: Arithmetic](
    nBanks: Int, nRows: Int, w: Int, sp_addr_t: SPAddr,
    inputType: T, accType: T, config: SystolicArrayConfig,
    val maxBytes: Int = 128, val dataBits: Int = 128)
    (implicit p: Parameters) extends LazyModule {

  import config._

  val block_rows = meshRows * tileRows
  val acc_w = (accType.getWidth / inputType.getWidth) * w

  val dataBytes = dataBits / 8
  val outFlits = block_rows * (w / dataBits)
  // val nXacts = ((w/8) - 1) / maxBytes + 1
  val nXacts = block_rows * (((w/8) - 1) / maxBytes + 1) // TODO is this right?
  val acc_nXacts = block_rows * (((acc_w/8) - 1) / maxBytes + 1) // TODO is this right?
  val Xacts_per_row = ((w/8) - 1) / maxBytes + 1
  val Xacts_per_acc_row = ((acc_w/8) - 1) / maxBytes + 1

  require(w % dataBits == 0)
  require(w <= (maxBytes*8)) // TODO get rid of this requirement

  val node = TLIdentityNode()
  val reader = LazyModule(new StreamReader(acc_nXacts, outFlits, maxBytes))
  val writer = LazyModule(new StreamWriter(nXacts, maxBytes))
  node := reader.node
  node := writer.node

  lazy val module = new LazyModuleImp(this) with HasCoreParameters {
    val io = IO(new Bundle {
      val dma = Flipped(new ScratchpadMemIO(nBanks, nRows, acc_rows))
      val read  = Flipped(Vec(nBanks, new ScratchpadReadIO(nRows, w)))
      val write = Flipped(Vec(nBanks, new ScratchpadWriteIO(nRows, w)))
      val tlb = new FrontendTLBIO

      // Accumulator ports
      val acc = new AccumulatorMemIO(acc_rows, Vec(meshColumns, Vec(tileColumns, accType)), Vec(meshColumns, Vec(tileColumns, inputType)))
    })

    require(reader.module.dataBits == dataBits)
    require(writer.module.dataBits == dataBits)

    val (s_idle :: s_translate_req :: s_translate_resp ::
         s_readreq :: s_readresp :: s_readwait ::
         s_writereq :: s_writedata :: s_writeresp ::
         s_respond :: Nil) = Enum(10)
    val state = RegInit(s_idle)
    val error = Reg(Bool())

    io.dma.req.ready := state === s_idle
    io.dma.resp.valid := state === s_respond
    io.dma.resp.bits.error := error

    val rowBytes = w / 8
    val nBeats = (w - 1) / dataBits + 1
    val rowAddrBits = log2Ceil(rowBytes)
    val byteAddrBits = log2Ceil(dataBytes)

    val acc_rowBytes = acc_w / 8
    val acc_nBeats = (acc_w - 1) / dataBits + 1
    val acc_rowAddrBits = log2Ceil(acc_rowBytes)

    val req = Reg(new ScratchpadMemRequest(nBanks, nRows, acc_rows))
    val reqVpn = req.vaddr(coreMaxAddrBits-1, pgIdxBits)
    val reqOffset = req.vaddr(pgIdxBits-1, 0)
    val reqPpn = Reg(UInt(ppnBits.W))
    val reqPaddr = Cat(reqPpn, reqOffset)
    val bytesLeft_len = if (reader.module.io.req.bits.length.getWidth % 2 == 0) reader.module.io.req.bits.length.getWidth+1 else reader.module.io.req.bits.length.getWidth // TODO inelegant
    val bytesLeft = Reg(UInt(bytesLeft_len.W))
    val req_rowBytes = Mux(!req.write && req.is_acc, acc_rowBytes.U, rowBytes.U) * req.len
    val next_row_vaddr = Reg(UInt(xLen.W)) // TODO Is the address space actually 64 bits?

    io.tlb.req.valid := state === s_translate_req
    io.tlb.req.bits.vaddr := Cat(reqVpn, 0.U(pgIdxBits.W))
    io.tlb.req.bits.passthrough := false.B
    io.tlb.req.bits.size := log2Ceil(dataBytes).U
    io.tlb.req.bits.cmd := Mux(req.write, M_XWR, M_XRD)
    io.tlb.resp.ready := state === s_translate_resp

    val tlberr = Mux(req.write,
      io.tlb.resp.bits.pf.st || io.tlb.resp.bits.ae.st,
      io.tlb.resp.bits.pf.ld || io.tlb.resp.bits.ae.ld)

    val nextVaddr = Cat(reqVpn + 1.U, 0.U(pgIdxBits.W))
    val pageBytes = nextVaddr - req.vaddr
    val lastPage = bytesLeft <= pageBytes
    val bytesAvail = Mux(lastPage, bytesLeft, pageBytes)
    val lastFlit = bytesAvail <= dataBytes.U
    val bytesToSend = Mux(lastFlit, bytesAvail, dataBytes.U)

    val stride = req.stride

    val row_counter = RegInit(0.U(log2Ceil(block_rows).W)) // Used to address into scratchpads
    val block_counter = RegInit(0.U(log2Ceil(maxBytes / rowBytes).W))
    val last_row = row_counter === 0.U // (block_rows-1).U
    val last_block = block_counter === (req.len - 1.U)

    val reader_req_row_counter = RegInit(0.U(log2Ceil(block_rows).W)) // TODO unify this with the other row_counter
    val reader_req_last_row = reader_req_row_counter === (block_rows-1).U
    val reader_row_id = Reg(reader.module.io.out.bits.id.cloneType)
    val reader_is_last = RegEnable(reader.module.io.out.bits.last, reader.module.io.out.fire())

    val req_full_addr = Cat(req.spbank, req.spaddr).asUInt()

    val readReq = Wire(new StreamReadRequest(nXacts))
    readReq.partial := !lastPage
    readReq.address := reqPaddr
    readReq.length := bytesAvail

    val writeReq = Wire(new StreamWriteRequest)
    writeReq.address := reqPaddr
    writeReq.length := bytesAvail

    reader.module.io.req.valid := state === s_readreq
    reader.module.io.req.bits := readReq
    reader.module.io.resp.ready := state === s_readresp

    writer.module.io.req.valid := state === s_writereq
    writer.module.io.req.bits := writeReq
    writer.module.io.resp.ready := state === s_writeresp

    reader.module.io.reset_Xacts := state === s_idle

    val rowBuffer = Reg(Vec(acc_nBeats, UInt(dataBits.W)))
    val bufAddr = Reg(UInt(acc_rowAddrBits.W))
    val bufIdx = (bufAddr >> byteAddrBits.U).asUInt()
    val bufDone = Reg(Bool())
    val rowBuffer_filled = bufIdx === Mux(!req.write && req.is_acc, (acc_nBeats-1).U, (nBeats-1).U)

    val dmardata = WireInit(0.U(w.W))
    val dmaren = WireInit(false.B)
    val dmawen = WireInit(false.B)

    when (req.write && ShiftRegister(dmaren, mem_pipeline+1)) {
      rowBuffer := dmardata.asTypeOf(rowBuffer)
    }

    val (rowData, rowKeep) = {
      // val bufAddr_delayed = RegNext(bufAddr) // TODO inelegant and inextensible for when mem_pipeline is larger than 1
      // val bytesToSend_delayed = RegNext(bytesToSend) // TODO inelegant and inextensible for when mem_pipeline is larger than 1
      // val bufIdx_delayed = RegNext(bufIdx) // TODO inelegant and inextensible for when mem_pipeline is larger than 1

      val offset = bufAddr(byteAddrBits-1, 0)
      val rshift = Cat(offset, 0.U(3.W))
      val lshift = Cat(dataBytes.U - offset, 0.U(3.W))

      val first = (rowBuffer(bufIdx) >> rshift).asUInt()
      val second = (rowBuffer(bufIdx + 1.U) << lshift).asUInt()

      val data = first | second
      val nbytes = bytesToSend(byteAddrBits, 0)
      val bytemask = (1.U << nbytes).asUInt() - 1.U

      (data(dataBits-1, 0), bytemask(dataBytes-1, 0))
    }

    reader.module.io.out.ready := true.B // !bufDone // TODO can we make this always true?

    writer.module.io.in.valid := (0 to mem_pipeline).map(i => ShiftRegister(state === s_writedata, i)).reduce(_ && _) // TODO inefficient. Reduces throughput when mem_pipeline > 1
    // writer.module.io.in.valid := state =/= s_writedata && RegNext(state === s_writedata) // TODO inelegant and inextensible for when mem_pipeline is larger than 1
    writer.module.io.in.bits.data := rowData
    writer.module.io.in.bits.keep := rowKeep
    writer.module.io.in.bits.last := lastFlit

    val banks = Seq.fill(nBanks) { Module(new ScratchpadBank(nRows, w, mem_pipeline)) }

    val dmawen_addr = (req_full_addr + reader_row_id + (block_counter * block_rows.U)).asTypeOf(sp_addr_t)
    val dmawen_spbank = dmawen_addr.bank
    val dmawen_sprow = dmawen_addr.row
    val dmawen_accrow = req.accaddr + reader_row_id + (block_counter * block_rows.U)

    for (i <- 0 until nBanks) {
      val bank = banks(i)
      val read = io.read(i)
      val write = io.write(i)
      val bankren = dmaren && req.spbank === i.U && !req.is_acc
      val bankwen = dmawen && dmawen_spbank === i.U && !req.is_acc

      bank.io.read.en := bankren || read.en
      bank.io.read.addr := Mux(bankren, req.spaddr + row_counter, read.addr)
      read.data := bank.io.read.data
      when (req.spbank === i.U && !req.is_acc) {
        // TODO this may need to change when moving out multiple matrices in one instruction is supported
        dmardata := bank.io.read.data
      }

      bank.io.write.en := bankwen || write.en
      bank.io.write.addr := Mux(bankwen, dmawen_sprow, write.addr)
      bank.io.write.data := Mux(bankwen, rowBuffer.asUInt(), write.data)
    }

    {
      val acc_row_t = Vec(meshColumns, Vec(tileColumns, accType))
      val spad_row_t = Vec(meshColumns, Vec(tileColumns, inputType))

      val accumulator = Module(new AccumulatorMem(acc_rows, acc_row_t, spad_row_t, mem_pipeline))
      val accbankren = dmaren && req.is_acc
      val accbankwen = dmawen && req.is_acc

      accumulator.io.read.en := accbankren || io.acc.read.en
      accumulator.io.read.addr := Mux(accbankren, req.accaddr + row_counter, io.acc.read.addr)
      accumulator.io.read.shift := io.acc.read.shift
      accumulator.io.read.relu6_shift := io.acc.read.relu6_shift
      accumulator.io.read.act := io.acc.read.act
      io.acc.read.data := accumulator.io.read.data
      when(req.is_acc) {
        dmardata := accumulator.io.read.data.asUInt()
      }

      accumulator.io.write.en := accbankwen || io.acc.write.en
      accumulator.io.write.addr := Mux(accbankwen, dmawen_accrow, io.acc.write.addr)
      accumulator.io.write.data := Mux(accbankwen, rowBuffer.asUInt(), io.acc.write.data.asUInt()).asTypeOf(acc_row_t)
      accumulator.io.write.acc := !accbankwen && io.acc.write.acc // TODO add ability to mvin to accumulating memory space from main memory
    }

    when (io.dma.req.fire()) {
      req := io.dma.req.bits
      next_row_vaddr := io.dma.req.bits.vaddr + io.dma.req.bits.stride
      bufAddr := 0.U
      bufDone := false.B
      bytesLeft := Mux(!io.dma.req.bits.write && io.dma.req.bits.is_acc, acc_rowBytes.U, rowBytes.U) * io.dma.req.bits.len

      state := s_translate_req
    }

    when (io.tlb.req.fire()) {
      when (req.write) {
        dmaren := true.B
        bufDone := true.B
      }

      state := s_translate_resp
    }

    when (io.tlb.resp.fire()) {
      when (tlberr) {
        error := true.B
        state := s_respond
      } .otherwise {
        reqPpn := io.tlb.resp.bits.paddr >> pgIdxBits.U
        state := Mux(req.write, s_writereq, s_readreq)
      }
    }

    when (reader.module.io.req.fire()) {
      bytesLeft := bytesLeft - bytesAvail
      state := s_readresp
    }

    when (reader.module.io.resp.fire()) {
      // TODO try to do this without taking a detour through the s_readresp state
      val across_pages = bytesLeft =/= 0.U

      when (across_pages) {
        req.vaddr := nextVaddr
        state := s_translate_req
      }.otherwise {
        // Pick the next virtual address
        req.vaddr := next_row_vaddr
        next_row_vaddr := next_row_vaddr + stride

        // Increment the row_counter
        reader_req_row_counter := reader_req_row_counter + 1.U

        // Choose next state
        when (reader_req_last_row) {
          state := s_readwait
        }.otherwise {
          // Start reading new row
          bytesLeft := req_rowBytes

          val new_vaddr = next_row_vaddr
          val new_page = new_vaddr(coreMaxAddrBits-1, pgIdxBits) =/= req.vaddr(coreMaxAddrBits-1, pgIdxBits)

          state := Mux(new_page, s_translate_req, s_readreq)
        }
      }
    }

    when (reader.module.io.out.fire()) {
      rowBuffer(bufIdx) := reader.module.io.out.bits.data
      bufAddr := bufAddr + dataBytes.U

      when (rowBuffer_filled) {
        reader_row_id := reader.module.io.out.bits.id / Mux(req.is_acc, Xacts_per_acc_row.U, Xacts_per_row.U)
        bufDone := true.B
        bufAddr := 0.U
      }
    }

    // when (state === s_writedata && writer.module.io.in.ready) { // TODO inelegant and inextensible for when mem_pipeline is larger than 1
    when (writer.module.io.in.fire()) {
      bufAddr := bufAddr + bytesToSend
      req.vaddr := req.vaddr + bytesToSend
      bytesLeft := bytesLeft - bytesToSend

      val across_pages = bytesLeft =/= bytesToSend // TODO think more deeply about this

      // TODO clean this up
      when (lastFlit) {
        val new_page = next_row_vaddr(coreMaxAddrBits-1, pgIdxBits) =/= req.vaddr(coreMaxAddrBits-1, pgIdxBits)
        req.vaddr := next_row_vaddr
        next_row_vaddr := next_row_vaddr + stride

        when (last_row || across_pages) {
          state := s_writeresp
        }.otherwise {
          bufAddr := 0.U
          bufDone := false.B
          bytesLeft := req_rowBytes

          when (!new_page) {
            dmaren := true.B
            state := s_writereq
          }.otherwise {
            // Since s_translate_req already sets dmaren to true.B, we don't have to do that here as well
            // TODO can we handle row accesses across page boundaries here?
            state := s_translate_req
          }
        }
      }
    }

    when (writer.module.io.req.fire()) {
      row_counter := wrappingAdd(row_counter, 1.U, block_rows)
      state := s_writedata
    }

    when (writer.module.io.resp.fire()) {
      error := false.B
      state := Mux(bytesLeft === 0.U, s_respond, s_translate_req)
    }

    when (state =/= s_idle && !req.write && bufDone) {
      dmawen := true.B
      error := false.B
      // bufAddr := 0.U
      bufDone := false.B

      block_counter := block_counter + 1.U

      when (last_block) {
        row_counter := row_counter + 1.U
      }

      when (reader_is_last) {
        block_counter := 0.U
      }

      when (row_counter === (block_rows-1).U && reader_is_last && last_block) {
        state := s_respond
      }
    }

    when (io.dma.resp.fire()) {
      row_counter := 0.U // TODO move these to io.dma.req.fire() instead
      block_counter := 0.U
      reader_req_row_counter := 0.U
      state := s_idle
    }

    assert(!(io.dma.req.fire() && io.dma.req.bits.vaddr % dataBytes.U =/= 0.U)) // TODO support non-aligned addresses
    assert(!(io.dma.req.fire() && io.dma.req.bits.write && io.dma.req.bits.len > 1.U)) // TODO add a row length option for writes
  }
}
