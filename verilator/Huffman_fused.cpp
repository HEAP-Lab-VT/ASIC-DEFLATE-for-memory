#include "verilated.h"
#include <stdio.h>
#include <stdbool.h>

// /<editor-fold> ugly pre-processor macros
#define _STR(s) #s
#define STR(s) _STR(s)
#define _CAT(s,t) s##t
#define CAT(s,t) _CAT(s,t)
// /</editor-fold>

#ifndef A_MODNAME
#define A_MODNAME HuffmanCompressor
#endif
#define A_VMODNAME CAT(V,A_MODNAME)
#include STR(A_VMODNAME.h)

#ifndef B_MODNAME
#define B_MODNAME HuffmanDecompressor
#endif
#define B_VMODNAME CAT(V,B_MODNAME)
#include STR(B_VMODNAME.h)

#ifndef TRACE_ENABLE
#define TRACE_ENABLE false
#endif
#if TRACE_ENABLE
#define A_DO_TRACE \
  do{ \
    if(a_trace_enable) { \
      a_trace->dump(a_traceTime); \
      a_traceTime++; \
    } \
  } while(0)
#define B_DO_TRACE \
  do{ \
    if(b_trace_enable) { \
      b_trace->dump(b_traceTime); \
      b_traceTime++; \
    } \
  } while(0)
#else
#define A_DO_TRACE while(0)
#define B_DO_TRACE while(0)
#endif

#if TRACE_ENABLE
#include "verilated_vcd_c.h"
#endif

#ifndef CHANNELS
#define CHANNELS 8
#endif

#define A_PASS2_BUF 8192

#ifndef A_IN_CHARS
#define A_IN_CHARS 8
#endif
#ifndef A_COUNT_CHARS
#define A_COUNT_CHARS 8
#endif
#ifndef A_OUT_CHARS
#define A_OUT_CHARS 24
#endif

#ifndef B_IN_CHARS
#define B_IN_CHARS 24
#endif
#ifndef B_OUT_CHARS
#define B_OUT_CHARS 8
#endif

#define MID_CHARS ((A_OUT_CHARS + B_IN_CHARS) * 2)

#ifndef TIMEOUT
#define TIMEOUT ( \
  a_idle >= 1000 || \
  b_idle >= 1000 || \
  a_cycles >= 20000 || \
  b_cycles >= 20000 || \
  iterations >= 40000 )
#endif

static size_t min(size_t a, size_t b) {return a <= b ? a : b;}
static size_t max(size_t a, size_t b) {return a >= b ? a : b;}


static FILE *inf = stdin;
static FILE *outf = stdout;

static A_VMODNAME *a_module;
static B_VMODNAME *b_module;

static char pass2Buf[A_PASS2_BUF];
static size_t pass2BufIdx = 0;
static size_t pass2BufInIdx = 0;
static size_t pass2BufCountIdx = 0;

static char inBuf[A_IN_CHARS];
static size_t inBufIdx = 0;
static bool inBufLast = false;
static char countBuf[A_COUNT_CHARS];
static size_t countBufIdx = 0;
static bool countBufLast = false;
static char midBuf[CHANNELS][MID_CHARS];
static size_t midBufIdx[CHANNELS];
static bool midBufLast[CHANNELS];
static char outBuf[B_OUT_CHARS];
static size_t outBufIdx = 0;
static bool outBufLast = false;

static int iterations = 0;
static int a_cycles = 0;
static int a_idle = 0;
static int a_idle_suspend = 0;
static int b_cycles = 0;
static int b_idle = 0;
static int b_idle_suspend = 0;

#if TRACE_ENABLE
static bool a_trace_enable;
static VerilatedVcdC* a_trace;
static int a_traceTime = 0;
static bool b_trace_enable;
static VerilatedVcdC* b_trace;
static int b_traceTime = 0;
#endif

static void a_step();
static void b_step();


int main(int argc, char **argv, char **env) {
  Verilated::commandArgs(argc, argv);
  a_module = new A_VMODNAME;
  b_module = new B_VMODNAME;
  
#if TRACE_ENABLE
  a_trace_enable = argc > 3 && (argv[3][0] != '-' || argv[3][1] != '\0');
  if(a_trace_enable) {
    Verilated::traceEverOn(true);
    a_trace = new VerilatedVcdC;
    a_module->trace(a_trace, 99);
    a_trace->open(argv[3]);
  }
  
  b_trace_enable = argc > 4 && (argv[4][0] != '-' || argv[4][1] != '\0');
  if(b_trace_enable) {
    Verilated::traceEverOn(true);
    b_trace = new VerilatedVcdC;
    b_module->trace(b_trace, 99);
    b_trace->open(argv[4]);
  }
#endif
  
  inf = stdin;
  outf = stdout;
  if(argc > 1 && (argv[1][0] != '-' || argv[1][1] != '\0'))
    inf = fopen(argv[1], "r");
  if(argc > 2 && (argv[2][0] != '-' || argv[2][1] != '\0'))
    outf = fopen(argv[2], "w");
  
  
  for(int chan = 0; chan < CHANNELS; chan++) {
    midBufIdx[chan] = 0;
    midBufLast[chan] = false;
  }
  
  // initialize A
  a_module->reset = 1;
  a_module->clock = 0;
  a_module->eval();
  a_module->clock = 1;
  a_module->eval();
  a_module->reset = 0;
  
  // initialize B
  b_module->reset = 1;
  b_module->clock = 0;
  b_module->eval();
  b_module->clock = 1;
  b_module->eval();
  b_module->reset = 0;
  
  
  do {
    
    // read input stream to input buffer
    size_t pass2BufTarg = max(
      A_IN_CHARS - inBufIdx + pass2BufInIdx,
      A_COUNT_CHARS - countBufIdx + pass2BufCountIdx);
    if(pass2BufIdx < pass2BufTarg) {
      pass2BufIdx +=
        fread(pass2Buf + pass2BufIdx, 1, A_PASS2_BUF - pass2BufIdx, inf);
    }
    
    while(inBufIdx < A_IN_CHARS && pass2BufInIdx < A_PASS2_BUF && !inBufLast) {
      inBuf[inBufIdx] = pass2Buf[pass2BufInIdx];
      inBufIdx++;
      pass2BufInIdx++;
      inBufLast = inBufLast || (pass2BufInIdx == pass2BufIdx && feof(inf));
    }
    while(countBufIdx < A_COUNT_CHARS && pass2BufCountIdx <= A_PASS2_BUF &&
        !countBufLast) {
      countBuf[countBufIdx] = pass2Buf[pass2BufCountIdx];
      countBufIdx++;
      pass2BufCountIdx++;
      countBufLast = countBufLast ||
        (pass2BufCountIdx == pass2BufIdx && feof(inf));
    }
    
    
    // decide whether module A should take a step
    bool go_a = false;
    // enough space in output
    for(int chan = 0; chan < CHANNELS; chan++) {
      go_a = go_a || (midBufIdx[chan] <= MID_CHARS - A_OUT_CHARS);
    }
    // enough input
    go_a = go_a && (inBufIdx == A_IN_CHARS || inBufLast);
    go_a = go_a && (countBufIdx == A_IN_CHARS || countBufLast);
    // b is moving
    go_a = go_a || b_idle_suspend >= 3;
    // not finished
    for(int chan = 0; true; chan++) {
      if(chan == CHANNELS) {
        go_a = false;
        break;
      }
      if(!midBufLast[chan])
        break;
    }
    
    if(go_a) {
      a_step();
    }
    
    // decide whether module B should take a step
    bool go_b = true;
    // enough input
    for(int chan = 0; chan < CHANNELS; chan++)
      go_b = go_b && (midBufLast[chan]);
    for(int chan = 0; chan < CHANNELS; chan++)
      go_b = go_b || (midBufIdx[chan] >= B_IN_CHARS);
    // enough space in output
    go_b = go_b && (outBufIdx == 0 || outBufLast);
    // a is moving
    go_b = go_b || a_idle_suspend >= 3;
    // not finished
    go_b = go_b && !outBufLast;
    
    if(go_b) {
      b_step();
    }
    
    // if neither 
    if(!go_a && !go_b) {
      a_step();
      b_step();
    }
    
    // write output buffer to output stream
    size_t c = fwrite(outBuf, 1, outBufIdx, outf);
    for(int i = c; i < outBufIdx; i++)
      outBuf[i + c] = outBuf[i];
    outBufIdx -= c;
    
    iterations++;
  } while((!outBufLast || outBufIdx) && !TIMEOUT);
	
	a_module->final();
	b_module->final();

  if(inf != stdin)
    fclose(inf);
  if(outf != stdout)
    fclose(outf);
  
#if TRACE_ENABLE
  if(a_trace_enable) {
    a_trace->close();
  }
  if(b_trace_enable) {
    b_trace->close();
  }
#endif
  
  delete a_module;
  delete b_module;
  
  fprintf(stderr, "cycles: %5d %5d %s\n",
    a_cycles, b_cycles,
    TIMEOUT ? "(timeout)" : "");
  
  return 0;
}


struct a_out_decoupledstream {
  decltype(A_VMODNAME::io_out_0_ready) ready;
  decltype(A_VMODNAME::io_out_0_valid) valid;
  decltype(A_VMODNAME::io_out_0_data_0) data[A_OUT_CHARS];
  decltype(A_VMODNAME::io_out_0_last) last;
};

struct b_in_decoupledstream {
  decltype(B_VMODNAME::io_in_0_ready) ready;
  decltype(B_VMODNAME::io_in_0_valid) valid;
  decltype(B_VMODNAME::io_in_0_data_0) data[B_IN_CHARS];
  decltype(B_VMODNAME::io_in_0_last) last;
};

static void a_step() {
  
  // module output is not in array form, so must use an ugly cast
  struct a_out_decoupledstream *a_out =
    (struct a_out_decoupledstream*)&a_module->io_out_0_ready;
  
  a_cycles++;
  a_idle++;
  a_idle_suspend++;
  
  // make sure any external changes are evaluated
  a_module->eval();
  
  // expose input buffer to module
  // module input is not in array form, so must use ugly cast
  for(int i = 0; i < A_IN_CHARS; i++) {
    (&a_module->io_in_compressor_data_0)[i] = inBuf[i]; // very ugly cast
  }
  for(int i = 0; i < A_COUNT_CHARS; i++) {
    (&a_module->io_in_counter_data_0)[i] = countBuf[i]; // very ugly cast
  }
  
  // assert valid input count and ready output count
  a_module->io_in_compressor_valid = inBufIdx;
  a_module->io_in_compressor_last = inBufLast;
  a_module->io_in_counter_valid = countBufIdx;
  a_module->io_in_counter_last = countBufLast;
  for(int chan = 0; chan < CHANNELS; chan++)
    a_out[chan].ready = min(MID_CHARS - midBufIdx[chan], A_OUT_CHARS);
  
  // update module outputs based on new inputs
  a_module->eval();
  
  // shift input buffer by number of characters consumed by module input
  size_t c =
    min(a_module->io_in_compressor_valid, a_module->io_in_compressor_ready);
  if(c) a_idle_suspend = a_idle = 0;
  inBufIdx -= c;
  for(int i = 0; i < inBufIdx; i++)
    inBuf[i] = inBuf[i + c];
  
  c = min(a_module->io_in_counter_valid, a_module->io_in_counter_ready);
  if(c) a_idle_suspend = a_idle = 0;
  countBufIdx -= c;
  for(int i = 0; i < countBufIdx; i++)
    countBuf[i] = countBuf[i + c];
  
  // push module output onto the end of output buffer
  for(int chan = 0; chan < CHANNELS; chan++) {
    c = min(a_out[chan].valid, a_out[chan].ready);
    if(c) b_idle_suspend = a_idle_suspend = a_idle = 0;
    for(int i = 0; i < c; i++)
      midBuf[chan][i + midBufIdx[chan]] = a_out[chan].data[i];
    midBufIdx[chan] += c;
    
    midBufLast[chan] = a_out[chan].last && c == a_out[chan].valid;
  }
  
  // put all the important information in the trace graph
  a_module->eval();
  A_DO_TRACE;
  
  // falling edge
  a_module->clock = 0;
  a_module->eval();
  A_DO_TRACE;
  
  // update registers with rising edge
  a_module->clock = 1;
  a_module->eval();
  // do not trace here because things are yet to be updated
}

static void b_step() {
  
  // module output is not in array form, so must use an ugly cast
  struct b_in_decoupledstream *b_in =
    (struct b_in_decoupledstream*)&b_module->io_in_0_ready;
  
  b_cycles++;
  b_idle++;
  b_idle_suspend++;
  
  // make sure any external changes are evaluated
  b_module->eval();
  
  for(int chan = 0; chan < CHANNELS; chan++) {
    b_in[chan].valid = min(midBufIdx[chan], B_IN_CHARS);
    b_in[chan].last = midBufLast[chan] && midBufIdx[chan] <= B_IN_CHARS;
    for(int i = 0; i < B_IN_CHARS; i++) {
      b_in[chan].data[i] = midBuf[chan][i];
    }
  }
  
  b_module->io_out_ready = B_OUT_CHARS - outBufIdx;
  
  // update module outputs based on new inputs
  b_module->eval();
  
  // shift input buffer by number of characters consumed by module input
  for(int chan = 0; chan < CHANNELS; chan++) {
    size_t c = min(b_in[chan].valid, b_in[chan].ready);
    if(c) a_idle_suspend = b_idle_suspend = b_idle = 0;
    midBufIdx[chan] -= c;
    for(int i = 0; i < midBufIdx[chan]; i++)
      midBuf[chan][i] = midBuf[chan][i + c];
  }
  
  // push module output onto the end of output buffer
  size_t c = min(b_module->io_out_valid, b_module->io_out_ready);
  if(c) b_idle_suspend = b_idle = 0;
  for(int i = 0; i < c; i++)
    outBuf[i + outBufIdx] = (&b_module->io_out_data_0)[i];
  outBufIdx += c;
  
  outBufLast = b_module->io_out_last && c == b_module->io_out_valid;
  
  // put all the important information in the trace graph
  b_module->eval();
  B_DO_TRACE;
  
  // falling edge
  b_module->clock = 0;
  b_module->eval();
  B_DO_TRACE;
  
  // update registers with rising edge
  b_module->clock = 1;
  b_module->eval();
  // do not trace here because things are yet to be updated
}
