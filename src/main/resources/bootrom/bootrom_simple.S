#define BOOTADDR_REG 0x4000

// boot all cores (only hart 0) and jump to main program execution
.section .text.start, "ax", @progbits
.globl _start
_start:
  li a1, 0x2000000 // base address of clint
  li a0, 8               // MIE or MSIP bit
  csrw mie, a0           // set only MSIP in MIE CSR
  csrs mstatus, a0       // set MIE in mstatus CSR, open interrupts
  la a0, _hang80          // on MSIP interrupt, go to _hang80
  csrw mtvec, a0

wfi_loop: // wait for MSIP interrupt to start program execution
  wfi
  j wfi_loop

// jump to reset vector
.section .text.hang80, "ax", @progbits
.globl _hang80
_hang80:
  j _hang

_hang:
  j boot_core_hart0

// other harts must wait until MSIP of hart 0 is cleared to continue
boot_core:
  lw t0, 0(a1)       // load hart 0 MSIP
  bnez t0, boot_core // block until hart 0 clears own MSIP
  sll a0, a0, 2      // offset for hart MSIP
  add a1, a0, a1
boot_core_hart0:      // begin executing code at DRAM_BASE
  sw zero, 0(a1)      // clear the interrupt
  li a0, BOOTADDR_REG // program reset vector
#if __riscv_xlen == 32
  lw a0, 0(a0)        // get boot address from bootaddr_reg SCR
#else
  ld a0, 0(a0)        // get boot address from bootaddr_reg SCR
#endif
  csrw mepc, a0       // return from interrupt to start of user program
  csrr a0, mhartid    // hartid for next level bootloader
  la a1, _dtb         // dtb address for next level bootloader
  li a2, 0x80         // set mstatus MPIE to 0
  csrc mstatus, a2
  mret

.align 3
_dtb:
