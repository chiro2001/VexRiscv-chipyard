#include "../common/framework.h"
#include "../common/jtag.h"
#include "../common/uart.h"
#include "VVexChip.h"
#include "VVexChip_VexChip.h"
#include "verilated.h"
#include "verilated_vcd_c.h"

class VexChipWorkspace : public Workspace<VVexChip> {
 public:
  VexChipWorkspace() : Workspace("VexChip") {
    uint64_t clockPeriod = (uint64_t)(1.0e12 / (100 * 1e6));
    // uint64_t clockPeriod = (uint64_t)(1.0e12 / (50 * 1e6));
    // uint64_t uart_rate = 115200;
    uint64_t uart_rate = 921600;
    ClockDomain *mainClk =
        new ClockDomain(&top->sys_clock, NULL, clockPeriod, 300000);
    AsyncReset *asyncReset = new AsyncReset(&top->reset, 50000);
    UartRx *uartRx = new UartRx(&top->uart_txd, 1.0e12 / uart_rate);
    UartTx *uartTx = new UartTx(&top->uart_rxd, 1.0e12 / uart_rate);

    timeProcesses.push_back(mainClk);
    timeProcesses.push_back(asyncReset);
    timeProcesses.push_back(uartRx);
    timeProcesses.push_back(uartTx);

    Jtag *jtag = new Jtag(&top->jtag_tms, &top->jtag_tdi, &top->jtag_tdo,
                          &top->jtag_tck, clockPeriod * 4);
    timeProcesses.push_back(jtag);

#ifdef TRACE
// speedFactor = 10e-3;
// cout << "Simulation caped to " << speedFactor << " of real time"<< endl;
#endif
  }
};

struct timespec timer_start() {
  struct timespec start_time;
  clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &start_time);
  return start_time;
}

long timer_end(struct timespec start_time) {
  struct timespec end_time;
  clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &end_time);
  uint64_t diffInNanos = end_time.tv_sec * 1e9 + end_time.tv_nsec -
                         start_time.tv_sec * 1e9 - start_time.tv_nsec;
  return diffInNanos;
}

int main(int argc, char **argv, char **env) {
  Verilated::randReset(2);
  Verilated::commandArgs(argc, argv);

  printf("BOOT\n");
  timespec startedAt = timer_start();

  VexChipWorkspace().run(1e9);

  uint64_t duration = timer_end(startedAt);
  cout << endl
       << "****************************************************************"
       << endl;

  exit(0);
}
