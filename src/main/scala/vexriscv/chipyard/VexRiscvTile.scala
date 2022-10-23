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

case class VexRiscvTileAttachParams(
                                 tileParams: VexRiscvTileParams,
                                 crossingParams: RocketCrossingParams
                               ) extends CanAttachTile {
  type TileType = VexRiscvTile
  val lookup = PriorityMuxHartIdFromSeq(Seq(tileParams))
}

// TODO: BTBParams, DCacheParams, ICacheParams are incorrect in DTB... figure out defaults in VexRiscv and put in DTB
case class VexRiscvTileParams(
                           name: Option[String] = Some("vexRiscv_tile"),
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

class VexRiscvTile private(
                        val vexRiscvParams: VexRiscvTileParams,
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

  // val memAXI4Nodes = AXI4MasterNode(
  //   Seq(
  //     AXI4MasterPortParameters(
  //     masters = Seq(AXI4MasterParameters(
  //       name = portName + "-imem",
  //       id = IdRange(0, 1 << idBits)))),
  //     AXI4MasterPortParameters(
  //       masters = Seq(AXI4MasterParameters(
  //         name = portName + "-dmem",
  //         id = IdRange(0, 1 << idBits))))
  //   ))

  val memAXI4Nodes = Seq(
    AXI4MasterNode(Seq(AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(
        name = portName + "-imem",
        id = IdRange(0, 1 << idBits)))))),
    AXI4MasterNode(Seq(AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(
        name = portName + "-dmem",
        id = IdRange(0, 1 << idBits))))))
  )

  val memoryTap = TLIdentityNode()
  val xbar = AXI4Xbar()
  (tlMasterXbar.node
    := memoryTap
    := TLBuffer()
    := TLFIFOFixer(TLFIFOFixer.all) // fix FIFO ordering
    := TLWidthWidget(beatBytes) // reduce size of TL
    := AXI4ToTL() // convert to TL
    := AXI4UserYanker(Some(2)) // remove user field on AXI interface. need but in reality user intf. not needed
    := AXI4Fragmenter() // deal with multi-beat xacts
    := xbar
    // := memAXI4Nodes
    )
  xbar := memAXI4Nodes.head
  xbar := memAXI4Nodes(1)

  def connectVexRiscvInterrupts(irq_line: UInt, soft_irq: Bool) {
    val (interrupts, _) = intSinkNode.in(0)
    // debug := interrupts(0)
    // msip := interrupts(1)
    // mtip := interrupts(2)
    // m_s_eip := Cat(interrupts(4), interrupts(3))
    irq_line := (interrupts.asUInt >> 1.U).asUInt
    soft_irq := interrupts.head
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
  val core = Module(new VexRiscv).suggestName("vexRiscv_core_inst")

  core.io.clk := clock
  core.io.rtc_clk := clock
  core.io.rst_n := ~reset.asBool
  core.io.cpu_rst_n := ~reset.asBool
  core.io.pwrup_rst_n := ~reset.asBool
  core.io.test_mode := false.B
  core.io.test_rst_n := ~reset.asBool
  // core.io.boot_addr_i := outer.resetVectorSinkNode.bundle
  core.io.fuse_mhartid := outer.hartIdSinkNode.bundle

  outer.connectVexRiscvInterrupts(core.io.irq_lines, core.io.soft_irq)

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
  outer.memAXI4Nodes.head.out.head match {
    case (out, edgeOut) =>
      core.io.io_axi_imem_awready := out.aw.ready
      out.aw.valid := core.io.io_axi_imem_awvalid
      out.aw.bits.id := core.io.io_axi_imem_awid
      out.aw.bits.addr := core.io.io_axi_imem_awaddr
      out.aw.bits.len := core.io.io_axi_imem_awlen
      out.aw.bits.size := core.io.io_axi_imem_awsize
      out.aw.bits.burst := core.io.io_axi_imem_awburst
      out.aw.bits.lock := core.io.io_axi_imem_awlock
      out.aw.bits.cache := core.io.io_axi_imem_awcache
      out.aw.bits.prot := core.io.io_axi_imem_awprot
      out.aw.bits.qos := core.io.io_axi_imem_awqos
      // unused signals
      assert(core.io.io_axi_imem_awregion === 0.U)
      assert(core.io.io_axi_imem_awuser === 0.U)

      core.io.io_axi_imem_wready := out.w.ready
      out.w.valid := core.io.io_axi_imem_wvalid
      out.w.bits.data := core.io.io_axi_imem_wdata
      out.w.bits.strb := core.io.io_axi_imem_wstrb
      out.w.bits.last := core.io.io_axi_imem_wlast
      // unused signals
      assert(core.io.io_axi_imem_wuser === 0.U)

      out.b.ready := core.io.io_axi_imem_bready
      core.io.io_axi_imem_bvalid := out.b.valid
      core.io.io_axi_imem_bid := out.b.bits.id
      core.io.io_axi_imem_bresp := out.b.bits.resp
      core.io.io_axi_imem_buser := 0.U // unused

      core.io.io_axi_imem_arready := out.ar.ready
      out.ar.valid := core.io.io_axi_imem_arvalid
      out.ar.bits.id := core.io.io_axi_imem_arid
      out.ar.bits.addr := core.io.io_axi_imem_araddr
      out.ar.bits.len := core.io.io_axi_imem_arlen
      out.ar.bits.size := core.io.io_axi_imem_arsize
      out.ar.bits.burst := core.io.io_axi_imem_arburst
      out.ar.bits.lock := core.io.io_axi_imem_arlock
      out.ar.bits.cache := core.io.io_axi_imem_arcache
      out.ar.bits.prot := core.io.io_axi_imem_arprot
      out.ar.bits.qos := core.io.io_axi_imem_arqos
      // unused signals
      assert(core.io.io_axi_imem_arregion === 0.U)
      assert(core.io.io_axi_imem_aruser === 0.U)

      out.r.ready := core.io.io_axi_imem_rready
      core.io.io_axi_imem_rvalid := out.r.valid
      core.io.io_axi_imem_rid := out.r.bits.id
      core.io.io_axi_imem_rdata := out.r.bits.data
      core.io.io_axi_imem_rresp := out.r.bits.resp
      core.io.io_axi_imem_rlast := out.r.bits.last
      core.io.io_axi_imem_ruser := 0.U // unused
  }
  outer.memAXI4Nodes.head.out.head match {
    case (out, edgeOut) =>
      core.io.io_axi_dmem_awready := out.aw.ready
      out.aw.valid := core.io.io_axi_dmem_awvalid
      out.aw.bits.id := core.io.io_axi_dmem_awid
      out.aw.bits.addr := core.io.io_axi_dmem_awaddr
      out.aw.bits.len := core.io.io_axi_dmem_awlen
      out.aw.bits.size := core.io.io_axi_dmem_awsize
      out.aw.bits.burst := core.io.io_axi_dmem_awburst
      out.aw.bits.lock := core.io.io_axi_dmem_awlock
      out.aw.bits.cache := core.io.io_axi_dmem_awcache
      out.aw.bits.prot := core.io.io_axi_dmem_awprot
      out.aw.bits.qos := core.io.io_axi_dmem_awqos
      // unused signals
      assert(core.io.io_axi_dmem_awregion === 0.U)
      assert(core.io.io_axi_dmem_awuser === 0.U)

      core.io.io_axi_dmem_wready := out.w.ready
      out.w.valid := core.io.io_axi_dmem_wvalid
      out.w.bits.data := core.io.io_axi_dmem_wdata
      out.w.bits.strb := core.io.io_axi_dmem_wstrb
      out.w.bits.last := core.io.io_axi_dmem_wlast
      // unused signals
      assert(core.io.io_axi_dmem_wuser === 0.U)

      out.b.ready := core.io.io_axi_dmem_bready
      core.io.io_axi_dmem_bvalid := out.b.valid
      core.io.io_axi_dmem_bid := out.b.bits.id
      core.io.io_axi_dmem_bresp := out.b.bits.resp
      core.io.io_axi_dmem_buser := 0.U // unused

      core.io.io_axi_dmem_arready := out.ar.ready
      out.ar.valid := core.io.io_axi_dmem_arvalid
      out.ar.bits.id := core.io.io_axi_dmem_arid
      out.ar.bits.addr := core.io.io_axi_dmem_araddr
      out.ar.bits.len := core.io.io_axi_dmem_arlen
      out.ar.bits.size := core.io.io_axi_dmem_arsize
      out.ar.bits.burst := core.io.io_axi_dmem_arburst
      out.ar.bits.lock := core.io.io_axi_dmem_arlock
      out.ar.bits.cache := core.io.io_axi_dmem_arcache
      out.ar.bits.prot := core.io.io_axi_dmem_arprot
      out.ar.bits.qos := core.io.io_axi_dmem_arqos
      // unused signals
      assert(core.io.io_axi_dmem_arregion === 0.U)
      assert(core.io.io_axi_dmem_aruser === 0.U)

      out.r.ready := core.io.io_axi_dmem_rready
      core.io.io_axi_dmem_rvalid := out.r.valid
      core.io.io_axi_dmem_rid := out.r.bits.id
      core.io.io_axi_dmem_rdata := out.r.bits.data
      core.io.io_axi_dmem_rresp := out.r.bits.resp
      core.io.io_axi_dmem_rlast := out.r.bits.last
      core.io.io_axi_dmem_ruser := 0.U // unused
  }
}
