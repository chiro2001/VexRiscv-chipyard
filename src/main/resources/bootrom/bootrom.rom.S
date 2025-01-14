#include "address.h"

.section .start, "ax", @progbits
.global _start
_start:
	csrr a0, mhartid                  # read current hart id
  bnez a0, park                     # if we're not on the hart 0
  // for sifive uart
  li a0,0x54000000
	// disable interrupts
	li a1,0
	sw a1,16(a0)
	// enable tx
	li a1,1
	sw a1,8(a0)
	// enable rx
	li a1,1
	sw a1,12(a0)

	// print B
	addi s3,zero,'B'
	sw s3,0(a0)
	j start_copy

_copy_flash:
	// rom_addr
	la a1, coremark
	// ddr_addr
	li a2, 0x80000000
	// program_size
	# li a3, (15*0x400*0x400)
	li a3, ((64 * 0x400) - 4) // set to 64kb
_copy_flash_loop:
	lw t0, 0(a1)
	sw t0, 0(a2)
	addi a1, a1, 4
	addi a2, a2, 4
	addi a3, a3, -4
	and s2, a1, s5
	bne s1, s2, skip_dot # if s1 == s2 then skip_dot
	sw s3,0(s4)
skip_dot:
	bnez a3, _copy_flash_loop
	ret

start_copy:
	li s1, (32 * 0x400)
	# li s3, '.'
	lui s4,0x54000000>>12
	li s5, 0xffff
  jal _copy_flash

	// print D
	addi a1,zero,'D'
	sw a1,0(a0)

  li a0, BOOTADDR_REG
  lw a0, 0(a0)
  csrw mepc, a0       // return from interrupt to start of user program
  csrr a0, mhartid    // hartid for next level bootloader
  la a1, _dtb         // dtb address for next level bootloader
  li a2, 0x80         // set mstatus MPIE to 0
  csrc mstatus, a2
  mret

.align 3
_dtb:

park:
	wfi
	j park

.align 8
coremark: