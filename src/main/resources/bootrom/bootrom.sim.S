#include "address.h"

.section .start, "ax", @progbits
.global _start
_start:
  // for sifive uart
  lui a0,0x54000000>>12
	// disable interrupts
	li a1,0
	sw a1,16(a0)
	// enable tx
	li a1,1
	sw a1,8(a0)
	// enable rx
	li a1,1
	sw a1,12(a0)

	// print START\n
	addi a1,zero,'S'
	addi a2,zero,'T'
	addi a3,zero,'A'
	addi a4,zero,'R'
	addi a5,zero,'T'
	addi a6,zero,'\n'
  sw a1,0(a0)
	sw a2,0(a0)
	sw a3,0(a0)
	sw a4,0(a0)
	sw a5,0(a0)
	sw a6,0(a0)
	j start_copy

// this boot ROM doesn't know about any boot devices, so it just spins,
// waiting for the serial interface to load the program and interrupt it
.section .text.hang, "ax", @progbits
.globl _hang
_hang: // reset vector
  la a0, _start          // on MSIP interrupt, go to _start
  csrw mtvec, a0
  csrr t0, misa          // get MISA mask
  srai t0, t0, 'S' - 'A' // get 'S' supervisor bit
  andi t0, t0, 0x1
  beqz t0, 1f            // only zero mideleg if supported
  csrw mideleg, zero     // no delegation
1:
  li a0, 8               // MIE or MSIP bit
  csrw mie, a0           // set only MSIP in MIE CSR
  csrs mstatus, a0       // set MIE in mstatus CSR

wfi_loop: // wait for MSIP interrupt to start program execution
  wfi
  j wfi_loop

_copy_flash:
	// flash_addr
	li a1, 0x20100000>>12
	// ddr_addr
	li a2, 0x80000000>>12
	// program_size
	# li a3, (15*0x400*0x400)
	li a3, ((256 * 0x400) - 1) // set to 256kb
_copy_flash_loop:
	lw t0, 0(a1)
	sw t0, 0(a2)
	addi a1, a1, 1
	addi a2, a2, 1
	addi a3, a3, -1
	bnez a3, _copy_flash_loop
	ret

start_copy:
  jal _copy_flash

  // for sifive uart
  lui a0,0x54000000>>12
	// disable interrupts
	li a1,0
	sw a1,16(a0)
	// enable tx
	li a1,1
	sw a1,8(a0)
	// enable rx
	li a1,1
	sw a1,12(a0)

	// print START\n
	addi a1,zero,'C'
	addi a2,zero,'O'
	addi a3,zero,'P'
	addi a4,zero,'Y'
	addi a5,zero,'.'
	addi a6,zero,'\n'
  sw a1,0(a0)
	sw a2,0(a0)
	sw a3,0(a0)
	sw a4,0(a0)
	sw a5,0(a0)
	sw a6,0(a0)

  li a0, BOOTADDR_REG
  lw a1, 0(a0)
  csrw mepc, a0       // return from interrupt to start of user program
  csrr a0, mhartid    // hartid for next level bootloader
  la a1, _dtb         // dtb address for next level bootloader
  li a2, 0x80         // set mstatus MPIE to 0
  csrc mstatus, a2
  mret

.align 3
_dtb:
