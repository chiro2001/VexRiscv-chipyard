//******************************************************************************
// Copyright (c) 2019 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// VexRiscv Tile Wrapper
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package vexriscv.chipyard

import chisel3._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.prci.ClockSinkParameters
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._

case class VexRiscvCoreParams
(bootFreqHz: BigInt = BigInt(1700000000))
  extends CoreParams {
  /* DO NOT CHANGE BELOW THIS */
  val useVM: Boolean = true
  val useHypervisor: Boolean = false
  val useUser: Boolean = true
  val useSupervisor: Boolean = false
  val useDebug: Boolean = true
  val useAtomics: Boolean = true
  val useAtomicsOnlyForIO: Boolean = false // copied from Rocket
  val useCompressed: Boolean = true
  override val useVector: Boolean = false
  val useSCIE: Boolean = false
  val useRVE: Boolean = false
  val mulDiv: Option[MulDivParams] = Some(MulDivParams()) // copied from Rocket
  val fpu: Option[FPUParams] = Some(FPUParams()) // copied fma latencies from Rocket
  val nLocalInterrupts: Int = 0
  val useNMI: Boolean = false
  val nPMPs: Int = 0 // TODO: Check
  val pmpGranularity: Int = 4 // copied from Rocket
  val nBreakpoints: Int = 0 // TODO: Check
  val useBPWatch: Boolean = false
  val mcontextWidth: Int = 0 // TODO: Check
  val scontextWidth: Int = 0 // TODO: Check
  val nPerfCounters: Int = 29
  val haveBasicCounters: Boolean = true
  val haveFSDirty: Boolean = false
  val misaWritable: Boolean = false
  val haveCFlush: Boolean = false
  val nL2TLBEntries: Int = 512 // copied from Rocket
  val nL2TLBWays: Int = 1
  val mtvecInit: Option[BigInt] = Some(BigInt(0)) // copied from Rocket
  val mtvecWritable: Boolean = true // copied from Rocket
  val instBits: Int = if (useCompressed) 16 else 32
  val lrscCycles: Int = 80 // copied from Rocket
  val decodeWidth: Int = 1 // TODO: Check
  val fetchWidth: Int = 1 // TODO: Check
  val retireWidth: Int = 2
  val nPTECacheEntries: Int = 8 // TODO: Check
}

case class VexRiscvTileAttachParams
(tileParams: VexRiscvTileParams,
 crossingParams: RocketCrossingParams
) extends CanAttachTile {
  type TileType = VexRiscvTile
  val lookup = PriorityMuxHartIdFromSeq(Seq(tileParams))
}

// TODO: BTBParams, DCacheParams, ICacheParams are incorrect in DTB... figure out defaults in VexRiscv and put in DTB
case class VexRiscvTileParams
(name: Option[String] = Some("vexRiscv_tile"),
 hartId: Int = 0,
 trace: Boolean = false,
 val core: VexRiscvCoreParams = VexRiscvCoreParams()
) extends InstantiableTileParams[VexRiscvTile] {
  val beuAddr: Option[BigInt] = None
  val blockerCtrlAddr: Option[BigInt] = None
  val btb: Option[BTBParams] = Some(BTBParams())
  val boundaryBuffers: Boolean = false
  val dcache: Option[DCacheParams] = Some(DCacheParams())
  val icache: Option[ICacheParams] = Some(ICacheParams())
  val clockSinkParams: ClockSinkParameters = ClockSinkParameters()

  def instantiate(crossing: TileCrossingParamsLike, lookup: LookupByHartIdImpl)(implicit p: Parameters): VexRiscvTile = {
    new VexRiscvTile(this, crossing, lookup)
  }
}

class VexRiscvTile private
(val vexRiscvParams: VexRiscvTileParams,
 crossing: ClockCrossingType,
 lookup: LookupByHartIdImpl,
 q: Parameters)
  extends BaseTile(vexRiscvParams, crossing, lookup, q)
    with SinksExternalInterrupts
    with SourcesExternalNotifications {
  /**
   * Setup parameters:
   * Private constructor ensures altered LazyModule.p is used implicitly
   */
  def this(params: VexRiscvTileParams, crossing: TileCrossingParamsLike, lookup: LookupByHartIdImpl)(implicit p: Parameters) =
    this(params, crossing.crossingType, lookup, p)

  val intOutwardNode = IntIdentityNode()
  val slaveNode = TLIdentityNode()
  val masterNode = visibilityNode

  tlOtherMastersNode := tlMasterXbar.node
  masterNode :=* tlOtherMastersNode
  DisableMonitors { implicit p => tlSlaveXbar.node :*= slaveNode }

  val cpuDevice: SimpleDevice = new SimpleDevice("cpu", Seq("openhwgroup,vexRiscv", "riscv")) {
    override def parent = Some(ResourceAnchors.cpus)

    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(name, mapping ++
        cpuProperties ++
        nextLevelCacheProperty ++
        tileProperties)
    }
  }

  ResourceBinding {
    Resource(cpuDevice, "reg").bind(ResourceAddress(staticIdForMetadataUseOnly))
  }

  override def makeMasterBoundaryBuffers(crossing: ClockCrossingType)(implicit p: Parameters) = crossing match {
    case _: RationalCrossing =>
      if (!vexRiscvParams.boundaryBuffers) TLBuffer(BufferParams.none)
      else TLBuffer(BufferParams.none, BufferParams.flow, BufferParams.none, BufferParams.flow, BufferParams(1))
    case _ => TLBuffer(BufferParams.none)
  }

  override def makeSlaveBoundaryBuffers(crossing: ClockCrossingType)(implicit p: Parameters) = crossing match {
    case _: RationalCrossing =>
      if (!vexRiscvParams.boundaryBuffers) TLBuffer(BufferParams.none)
      else TLBuffer(BufferParams.flow, BufferParams.none, BufferParams.none, BufferParams.none, BufferParams.none)
    case _ => TLBuffer(BufferParams.none)
  }

  override lazy val module = new VexRiscvTileModuleImp(this)

  /**
   * Setup AXI4 memory interface.
   * THESE ARE CONSTANTS.
   */
  val portName = "vexRiscv-mem-port-axi4"
  val idBits = 4
  val beatBytes = masterPortBeatBytes
  val sourceBits = 1 // equiv. to userBits (i think)

  val memAXI4Nodes = Seq(
    AXI4MasterNode(Seq(AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(portName + "-imem"))))),
    AXI4MasterNode(Seq(AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(portName + "-dmem")))))
  )
  // val memAXI4Node =
  // AXI4MasterNode(Seq(
  //   AXI4MasterPortParameters(masters = Seq(AXI4MasterParameters(portName + "-imem"))),
  //   AXI4MasterPortParameters(masters = Seq(AXI4MasterParameters(portName + "-dmem")))))

  val memoryTap = TLIdentityNode()

  val useTLXBar = false
  // val useTLXBar = true

  if (useTLXBar) {
    val xbar = TLXbar()
    (tlMasterXbar.node
      := memoryTap
      := TLBuffer()
      := xbar)
    (xbar := TLBuffer() := TLFIFOFixer(TLFIFOFixer.all) // fix FIFO ordering
      := TLWidthWidget(beatBytes) // reduce size of TL
      := AXI4ToTL() // convert to TL
      := AXI4UserYanker(Some(2)) // remove user field on AXI interface. need but in reality user intf. not needed
      := AXI4Fragmenter() // deal with multi-beat xacts
      := memAXI4Nodes.head)
    (xbar := TLBuffer() := TLFIFOFixer(TLFIFOFixer.all) // fix FIFO ordering
      := TLWidthWidget(beatBytes) // reduce size of TL
      := AXI4ToTL() // convert to TL
      := AXI4UserYanker(Some(2)) // remove user field on AXI interface. need but in reality user intf. not needed
      := AXI4Fragmenter() // deal with multi-beat xacts
      := memAXI4Nodes(1))
  } else {
    val xbar = AXI4Xbar()
    (tlMasterXbar.node
      := memoryTap
      := TLBuffer()
      := TLFIFOFixer(TLFIFOFixer.all) // fix FIFO ordering
      := TLWidthWidget(beatBytes) // reduce size of TL
      := AXI4ToTL() // convert to TL
      := AXI4UserYanker(Some(2)) // remove user field on AXI interface. need but in reality user intf. not needed
      := AXI4Fragmenter() // deal with multi-beat xacts
      := xbar)
    // xbar := AXI4UserYanker(Some(2)) := AXI4Fragmenter() := memAXI4Nodes.head
    // xbar := AXI4UserYanker(Some(2)) := AXI4Fragmenter() := memAXI4Nodes(1)
    xbar := memAXI4Nodes.head
    xbar := memAXI4Nodes(1)
  }

  // (tlMasterXbar.node
  //   := memoryTap
  //   := TLBuffer()
  //   := TLFIFOFixer(TLFIFOFixer.all) // fix FIFO ordering
  //   := TLWidthWidget(beatBytes) // reduce size of TL
  //   := AXI4ToTL() // convert to TL
  //   := AXI4UserYanker(Some(2)) // remove user field on AXI interface. need but in reality user intf. not needed
  //   := AXI4Fragmenter() // deal with multi-beat xacts
  //   := memAXI4Node)

  def connectVexRiscvInterrupts(ints: UInt) {
    val (interrupts, _) = intSinkNode.in(0)
    // debug := interrupts(0)
    // msip := interrupts(1)
    // mtip := interrupts(2)
    // m_s_eip := Cat(interrupts(4), interrupts(3))
    ints := interrupts.asUInt
  }
}

class VexRiscvTileModuleImp(outer: VexRiscvTile) extends BaseTileModuleImp(outer) {
  // annotate the parameters
  Annotated.params(this, outer.vexRiscvParams)

  val debugBaseAddr = BigInt(0x0) // CONSTANT: based on default debug module
  val debugSz = BigInt(0x1000) // CONSTANT: based on default debug module
  val tohostAddr = BigInt(0x80001000L) // CONSTANT: based on default sw (assume within extMem region)
  val fromhostAddr = BigInt(0x80001040L) // CONSTANT: based on default sw (assume within extMem region)

  // have the main memory, bootrom, debug regions be executable
  val bootromParams = p(BootROMLocated(InSubsystem)).get
  val executeRegionBases = Seq(p(ExtMem).get.master.base, bootromParams.address, debugBaseAddr, BigInt(0x0), BigInt(0x0))
  val executeRegionSzs = Seq(p(ExtMem).get.master.size, BigInt(bootromParams.size), debugSz, BigInt(0x0), BigInt(0x0))
  val executeRegionCnt = executeRegionBases.length

  // have the main memory be cached, but don't cache tohost/fromhost addresses
  // TODO: current cache subsystem can only support 1 cacheable region... so cache AFTER the tohost/fromhost addresses
  val wordOffset = 0x40
  val (cacheableRegionBases, cacheableRegionSzs) = if (true /* outer.vexRiscvParams.core.enableToFromHostCaching */ ) {
    val bases = Seq(p(ExtMem).get.master.base, BigInt(0x0), BigInt(0x0), BigInt(0x0), BigInt(0x0))
    val sizes = Seq(p(ExtMem).get.master.size, BigInt(0x0), BigInt(0x0), BigInt(0x0), BigInt(0x0))
    (bases, sizes)
  } else {
    val bases = Seq(fromhostAddr + 0x40, p(ExtMem).get.master.base, BigInt(0x0), BigInt(0x0), BigInt(0x0))
    val sizes = Seq(p(ExtMem).get.master.size - (fromhostAddr + 0x40 - p(ExtMem).get.master.base), tohostAddr - p(ExtMem).get.master.base, BigInt(0x0), BigInt(0x0), BigInt(0x0))
    (bases, sizes)
  }
  val cacheableRegionCnt = cacheableRegionBases.length

  // Add 2 to account for the extra clock and reset included with each
  // instruction in the original trace port implementation. These have since
  // been removed from TracedInstruction.
  val traceInstSz = (new freechips.rocketchip.rocket.TracedInstruction).getWidth + 2

  // connect the vexRiscv core
  val core = Module(new VexAXICore).suggestName("vexRiscv_core_inst")

  core.io.clk := clock
  core.io.io_axiClk := clock
  core.io.reset := reset.asBool
  core.io.io_asyncReset := reset.asBool

  outer.connectVexRiscvInterrupts(core.io.io_coreInterrupt)

  if (outer.vexRiscvParams.trace) {
    // unpack the trace io from a UInt into Vec(TracedInstructions)
    //outer.traceSourceNode.bundle <> core.io.trace_o.asTypeOf(outer.traceSourceNode.bundle)

    // TODO: add tracer
    // for (w <- 0 until outer.vexRiscvParams.core.retireWidth) {
    //   outer.traceSourceNode.bundle(w).valid := core.io.trace_o(traceInstSz * w + 2)
    //   outer.traceSourceNode.bundle(w).iaddr := core.io.trace_o(traceInstSz * w + 42, traceInstSz * w + 3)
    //   outer.traceSourceNode.bundle(w).insn := core.io.trace_o(traceInstSz * w + 74, traceInstSz * w + 43)
    //   outer.traceSourceNode.bundle(w).priv := core.io.trace_o(traceInstSz * w + 77, traceInstSz * w + 75)
    //   outer.traceSourceNode.bundle(w).exception := core.io.trace_o(traceInstSz * w + 78)
    //   outer.traceSourceNode.bundle(w).interrupt := core.io.trace_o(traceInstSz * w + 79)
    //   outer.traceSourceNode.bundle(w).cause := core.io.trace_o(traceInstSz * w + 87, traceInstSz * w + 80)
    //   outer.traceSourceNode.bundle(w).tval := core.io.trace_o(traceInstSz * w + 127, traceInstSz * w + 88)
    // }
  } else {
    outer.traceSourceNode.bundle := DontCare
    outer.traceSourceNode.bundle map (t => t.valid := false.B)
  }

  // connect the axi interface
  // require(outer.memAXI4Nodes.out.size == 2, "This core requires imem and dmem AXI ports!")
  require(outer.memAXI4Nodes.size == 2, "This core requires imem and dmem AXI ports!")
  outer.memAXI4Nodes(1).out.head match {
    case (out, edgeOut) =>
      core.io.io_dBus_arw_ready := out.aw.ready
      out.aw.valid := core.io.io_dBus_arw_valid & core.io.io_dBus_arw_payload_write
      out.aw.bits.id := 0.U
      out.aw.bits.addr := core.io.io_dBus_arw_payload_addr
      if (core.io.io_dBus_arw_payload_len.nonEmpty)
        out.aw.bits.len := core.io.io_dBus_arw_payload_len.get
      else out.aw.bits.len := 0.U
      out.aw.bits.size := core.io.io_dBus_arw_payload_size
      out.aw.bits.burst := "b01".U
      out.aw.bits.lock := "b00".U
      out.aw.bits.cache := core.io.io_dBus_arw_payload_cache
      out.aw.bits.prot := core.io.io_dBus_arw_payload_prot
      out.aw.bits.qos := "b0000".U

      core.io.io_dBus_w_ready := out.w.ready
      out.w.valid := core.io.io_dBus_w_valid
      out.w.bits.data := core.io.io_dBus_w_payload_data
      out.w.bits.strb := core.io.io_dBus_w_payload_strb
      out.w.bits.last := core.io.io_dBus_w_payload_last

      out.b.ready := core.io.io_dBus_b_ready
      core.io.io_dBus_b_valid := out.b.valid
      core.io.io_dBus_b_payload_resp := out.b.bits.resp

      core.io.io_dBus_arw_ready := out.ar.ready
      out.ar.valid := core.io.io_dBus_arw_valid & !core.io.io_dBus_arw_payload_write
      out.ar.bits.id := 0.U
      out.ar.bits.addr := core.io.io_dBus_arw_payload_addr
      out.ar.bits.len := 0.U
      out.ar.bits.size := core.io.io_dBus_arw_payload_size
      out.ar.bits.burst := "b01".U
      out.ar.bits.lock := "b00".U
      out.ar.bits.cache := core.io.io_dBus_arw_payload_cache
      out.ar.bits.prot := core.io.io_dBus_arw_payload_prot
      out.ar.bits.qos := "b0000".U

      out.r.ready := core.io.io_dBus_r_ready
      core.io.io_dBus_r_valid := out.r.valid
      core.io.io_dBus_r_payload_data := out.r.bits.data
      core.io.io_dBus_r_payload_resp := out.r.bits.resp
      core.io.io_dBus_r_payload_last := out.r.bits.last
  }
  outer.memAXI4Nodes.head.out.head match {
    case (out, edgeOut) =>
      // core.io.io_iBus_arw_ready := out.aw.ready
      // out.aw.valid := core.io.io_iBus_arw_valid
      // out.aw.bits.id := 0.U
      // out.aw.bits.addr := core.io.io_iBus_arw_payload_addr
      // out.aw.bits.len := 0.U
      // out.aw.bits.size := core.io.io_iBus_arw_payload_size
      // out.aw.bits.burst := "b01".U
      // out.aw.bits.lock := "b00".U
      // out.aw.bits.cache := core.io.io_iBus_arw_payload_cache
      // out.aw.bits.prot := core.io.io_iBus_arw_payload_prot
      // out.aw.bits.qos := "b0000".U
      out.aw := DontCare

      // core.io.io_iBus_w_ready := out.w.ready
      // out.w.valid := core.io.io_iBus_w_valid
      // out.w.bits.data := core.io.io_iBus_w_payload_data
      // out.w.bits.strb := core.io.io_iBus_w_payload_strb
      // out.w.bits.last := core.io.io_iBus_w_payload_last
      out.w := DontCare

      // out.b.ready := core.io.io_iBus_b_ready
      // core.io.io_iBus_b_valid := out.b.valid
      // core.io.io_iBus_b_payload_resp := out.b.bits.resp
      out.b := DontCare

      core.io.io_iBus_ar_ready := out.ar.ready
      out.ar.valid := core.io.io_iBus_ar_valid
      out.ar.bits.id := 0.U
      out.ar.bits.addr := core.io.io_iBus_ar_payload_addr
      if (core.io.io_iBus_ar_payload_len.nonEmpty)
        out.ar.bits.len := core.io.io_iBus_ar_payload_len.get
      else out.ar.bits.len := 0.U
      out.ar.bits.size := "b010".U
      if (core.io.io_iBus_ar_payload_burst.nonEmpty)
        out.ar.bits.burst := core.io.io_iBus_ar_payload_burst.get
      else out.ar.bits.burst := "b01".U
      out.ar.bits.lock := "b00".U
      out.ar.bits.cache := core.io.io_iBus_ar_payload_cache
      out.ar.bits.prot := core.io.io_iBus_ar_payload_prot
      out.ar.bits.qos := "b0000".U

      out.r.ready := core.io.io_iBus_r_ready
      core.io.io_iBus_r_valid := out.r.valid
      core.io.io_iBus_r_payload_data := out.r.bits.data
      core.io.io_iBus_r_payload_resp := out.r.bits.resp
      core.io.io_iBus_r_payload_last := out.r.bits.last
  }
}