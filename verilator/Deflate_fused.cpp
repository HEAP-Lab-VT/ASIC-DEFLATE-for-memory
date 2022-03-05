#include "verilated.h"
#include <stdio.h>
#include <stdbool.h>
#include "BitQueue.h"

// /<editor-fold> ugly pre-processor macros
#define _STR(s) #s
#define STR(s) _STR(s)
#define _CAT(s,t) s##t
#define CAT(s,t) _CAT(s,t)
// /</editor-fold>

#ifndef A_MODNAME
#define A_MODNAME DeflateCompressor
#endif
#define A_VMODNAME CAT(V,A_MODNAME)
#include STR(A_VMODNAME.h)

#ifndef B_MODNAME
#define B_MODNAME DeflateDecompressor
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

#ifndef A_IN_CHARS
#define A_IN_CHARS 8
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

#ifndef TIMEOUT
#define TIMEOUT ( \
  a_idle >= 10000 || \
  b_idle >= 10000 || \
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

static char inBuf[A_IN_CHARS];
static size_t inBufIdx = 0;
static bool inBufLast = false;
static struct BitQueue midBuf[CHANNELS];
static bool midBufLast[CHANNELS];
static char outBuf[B_OUT_CHARS];
static size_t outBufIdx = 0;
static bool outBufLast = false;

static size_t compressedSize = 0;

static int iterations = 0;
static int a_cycles = 0;
static int a_idle = 0;
static int b_cycles = 0;
static int b_idle = 0;

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
    bq_init(&midBuf[chan]);
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
    size_t bytesRead = fread(inBuf + inBufIdx, 1, A_IN_CHARS - inBufIdx, inf);
		inBufLast = inBufLast || feof(inf);
		inBufIdx += bytesRead;
    
    // decide whether module A should take a step
    bool go_a = false;
    // not enough output
    for(int chan = 0; chan < CHANNELS; chan++)
      go_a = go_a ||
        !(bq_size(&midBuf[chan]) >= B_IN_CHARS || midBufLast[chan]);
    // enough input
    go_a = go_a && (inBufIdx == A_IN_CHARS || inBufLast);
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
      go_b = go_b &&
        (bq_size(&midBuf[chan]) >= B_IN_CHARS || midBufLast[chan]);
    // enough space in output
    go_b = go_b && (outBufIdx == 0 || outBufLast);
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
  
  fprintf(stderr, "cycles = %5d %5d%s\n", a_cycles, b_cycles,
    TIMEOUT ? " (timeout)" : "");
  fprintf(stderr, "compressed size = %5lu\n", compressedSize / 8);
  
  return 0;
}


// define struct to match module inputs/outputs
// uses verilator types so the sizes match
struct a_out_decoupledstream {
  CData ready;
  CData valid;
  CData data[A_OUT_CHARS];
  CData last;
};
struct b_in_decoupledstream {
  CData ready;
  CData valid;
  CData data[B_IN_CHARS];
  CData last;
};

static void a_step() {
  
  // module output is not in array form, so must use an ugly cast
  struct a_out_decoupledstream *a_out =
    (struct a_out_decoupledstream*)&a_module->io_out_0_ready;
  
  a_cycles++;
  a_idle++;
  
  // make sure any external changes are evaluated
  a_module->eval();
  
  // expose input buffer to module
  // module input is not in array form, so must use ugly cast
  for(int i = 0; i < A_IN_CHARS; i++) {
    (&a_module->io_in_data_0)[i] = inBuf[i]; // very ugly cast
  }
  
  // assert valid input count and ready output count
  a_module->io_in_valid = inBufIdx;
  a_module->io_in_last = inBufLast;
  for(int chan = 0; chan < CHANNELS; chan++)
    a_out[chan].ready = A_OUT_CHARS;
  
  // update module outputs based on new inputs
  a_module->eval();
  
  // shift input buffer by number of characters consumed by module input
  size_t c =
    min(a_module->io_in_valid, a_module->io_in_ready);
  if(c) a_idle = 0;
  inBufIdx -= c;
  for(int i = 0; i < inBufIdx; i++)
    inBuf[i] = inBuf[i + c];
  
  // push module output onto the end of output buffer
  for(int chan = 0; chan < CHANNELS; chan++) {
    c = min(a_out[chan].valid, a_out[chan].ready);
    if(c) a_idle = 0;
    for(int i = 0; i < c; i++) {
      if(bq_pushTail(&midBuf[chan], a_out[chan].data[i]))
        {fprintf(stderr,"bq_pushTail failed\n");abort();}
      compressedSize++;
    }
    
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
  
  // make sure any external changes are evaluated
  b_module->eval();
  
  for(int chan = 0; chan < CHANNELS; chan++) {
    b_in[chan].valid = B_IN_CHARS;
    for(int i = 0; i < B_IN_CHARS; i++) {
      if(bq_pop(&midBuf[chan], (bool*)&b_in[chan].data[i])) {
        b_in[chan].valid = i;
        break;
      }
    }
    b_in[chan].last = midBufLast[chan] && bq_isEmpty(&midBuf[chan]);
  }
  
  b_module->io_out_ready = B_OUT_CHARS - outBufIdx;
  
  // update module outputs based on new inputs
  b_module->eval();
  
  // push back input characters that were not consumed
  for(int chan = 0; chan < CHANNELS; chan++) {
    if(b_in[chan].valid != 0 || b_in[chan].ready != 0) b_idle = 0;
    for(int i = b_in[chan].valid; i > b_in[chan].ready; i--) {
      if(bq_pushHead(&midBuf[chan], b_in[chan].data[i-1]))
        {fprintf(stderr,"bq_pushHead failed\n");abort();}
    }
  }
  
  // push module output onto the end of output buffer
  size_t c = min(b_module->io_out_valid, b_module->io_out_ready);
  if(c) b_idle = 0;
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
