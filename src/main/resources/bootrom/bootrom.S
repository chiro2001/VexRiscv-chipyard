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

  li gp, __STACKSIZE__
  li sp, __STACKSIZE__
  jal copy_flash

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
