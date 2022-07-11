#include "verilated.h"
#include <stdint.h>
#include <stdio.h>
#include <stdbool.h>


// <editor-fold> ugly pre-processor macros
#define _STR(s) #s
#define STR(s) _STR(s)
#define _CAT(s,t) s##t
#define CAT(s,t) _CAT(s,t)
// </editor-fold>

#ifndef COMPRESSOR
#define COMPRESSOR HuffmanCompressor
#endif
#ifndef DECOMPRESSOR
#define DECOMPRESSOR HuffmanDecompressor
#endif
#define VCOMPRESSOR CAT(V,COMPRESSOR)
#define VDECOMPRESSOR CAT(V,DECOMPRESSOR)
#include STR(VCOMPRESSOR.h)
#include STR(VDECOMPRESSOR.h)


#ifndef TRACE_ENABLE
#define TRACE_ENABLE false
#endif
#if TRACE_ENABLE
  #include "verilated_vcd_c.h"
  #define COMPRESSOR_TRACE() do \
    if(compressorTraceEnable) { \
      compressorTrace->dump(compressorContext->time()); \
      compressorContext->timeInc(1); \
    } while(false)
  #define DECOMPRESSOR_TRACE() do \
    if(decompressorTraceEnable) { \
      decompressorTrace->dump(decompressorContext->time()); \
      decompressorContext->timeInc(1); \
    } while(false)
#else
  #define COMPRESSOR_TRACE() do {} while(false)
  #define DECOMPRESSOR_TRACE() do {} while(false)
#endif


#ifndef TIMEOUT
#define TIMEOUT (idle >= 5000)
#endif


#define ARG_DUMP_FILENAME 1
#define ARG_REPORT_FILENAME 2
#define ARG_COMPRESSOR_TRACE 3
#define ARG_DECOMPRESSOR_TRACE 4

#define PAGE_SIZE 4096

#define STAGE_LOAD 0
#define STAGE_COMPRESSOR 1
#define STAGE_DECOMPRESSOR 2
#define STAGE_FINALIZE 3
#define NUM_STAGES 4
#define STAGE_FINISH -1

#define JOB_QUEUE_SIZE 10


static size_t min(size_t a, size_t b) {return a <= b ? a : b;}
static size_t max(size_t a, size_t b) {return a >= b ? a : b;}


struct Job {
  int stage;
  int id;
  
  uint8_t *raw;
  size_t rawLen;
  size_t rawCap;
  uint8_t *compressed;
  size_t compressedLen; // in bits, not bytes
  size_t compressedCap; // in bytes
  uint8_t *decompressed;
  size_t decompressedLen;
  size_t decompressedCap;
  
  int compressorCycles;
  int decompressorCycles;
  int compressorStallCycles;
  int decompressorStallCycles;
};
struct Summary {
  size_t totalSize;
  int totalPages;
  size_t nonzeroSize;
  int nonzeroPages;
  size_t compressedSize;
  
  int passedPages;
  int failedPages;
  
  int compressorCycles;
  int compressorStalls;
  int decompressorCycles;
  int decompressorStalls;
};
struct Options {
  const char *dump;
  const char *report;
  const char *cTrace;
  const char *dTrace;
  const char *debugJob;
};
static Options options;

static VCOMPRESSOR *compressor;
static VDECOMPRESSOR *decompressor;
static VerilatedContext *compressorContext;
static VerilatedContext *decompressorContext;
#if TRACE_ENABLE
static VerilatedVcdC *compressorTrace;
static VerilatedVcdC *decompressorTrace;
static bool compressorTraceEnable = false;
static bool decompressorTraceEnable = false;
#endif
static FILE *dumpfile;
static FILE *reportfile;
static Job jobs[JOB_QUEUE_SIZE];
static Summary summary;
static int debugJobId;
static bool quit;

static bool doLoad();
static bool doCompressor();
static bool doDecompressor();
static bool doFinalize();

static bool isFinished() {
  for(int i = 0; i < JOB_QUEUE_SIZE; i++) {
    if(jobs[i].stage != STAGE_FINISH && jobs[i].stage != STAGE_LOAD)
      return false;
  }
  for(int i = 0; i < JOB_QUEUE_SIZE; i++) {
    if(jobs[i].stage == STAGE_FINISH)
      return true;
  }
  return false;
}

static void cleanup() {
  compressor->final();
  decompressor->final();
  
  if(dumpfile != stdin)
  fclose(dumpfile);
  if(reportfile != stdout)
  fclose(reportfile);
  
  #if TRACE_ENABLE
  if(compressorTraceEnable) {
    compressorTrace->close();
    delete compressorContext;
  }
  if(decompressorTraceEnable) {
    decompressorTrace->close();
    delete decompressorContext;
  }
  #endif
  
  delete compressor;
  delete decompressor;
}

int main(int argc, const char **argv, char **env) {
  options.dump = "-";
  options.report = "-";
  options.cTrace = "-";
  options.dTrace = "-";
  options.debugJob = "-1";
  for(int i = 1; i < argc; i++) {
    if(!strcmp(argv[i], "--dump")) {
      ++i;
      assert(i < argc);
      options.dump = argv[i];
    }
    else if(!strcmp(argv[i], "--report")) {
      ++i;
      assert(i < argc);
      options.report = argv[i];
    }
    else if(!strcmp(argv[i], "--c-trace")) {
      ++i;
      assert(i < argc);
      options.cTrace = argv[i];
    }
    else if(!strcmp(argv[i], "--d-trace")) {
      ++i;
      assert(i < argc);
      options.dTrace = argv[i];
    }
    else if(!strcmp(argv[i], "--debug-job")) {
      ++i;
      assert(i < argc);
      options.debugJob = argv[i];
    }
  }
  debugJobId = atoi(options.debugJob);
  
  
  compressorContext = new VerilatedContext;
  decompressorContext = new VerilatedContext;
  compressorContext->commandArgs(argc, argv);
  decompressorContext->commandArgs(argc, argv);
  compressor = new VCOMPRESSOR{compressorContext, "TOP_COMPRESSOR"};
  decompressor = new VDECOMPRESSOR{decompressorContext, "TOP_DECOMPRESSOR"};
  
  #if TRACE_ENABLE
  compressorTraceEnable = !!strcmp(options.cTrace, "-");
  if(compressorTraceEnable) {
    compressorContext->traceEverOn(true);
    compressorTrace = new VerilatedVcdC;
    compressor->trace(compressorTrace, 99);
    compressorTrace->open(options.cTrace);
  }
  
  decompressorTraceEnable = !!strcmp(options.dTrace, "-");
  if(decompressorTraceEnable) {
    decompressorContext->traceEverOn(true);
    decompressorTrace = new VerilatedVcdC;
    decompressor->trace(decompressorTrace, 99);
    decompressorTrace->open(options.dTrace);
  }
  #endif
  
  dumpfile = stdin;
  if(strcmp(options.dump, "-"))
    dumpfile = fopen(options.dump, "r");
  
  reportfile = stdout;
  if(strcmp(options.report, "-"))
    reportfile = fopen(options.report, "w");
  
  for(int i = 0; i < JOB_QUEUE_SIZE; i++) {
    jobs[i].stage = 0;
    jobs[i].raw = NULL;
    jobs[i].rawLen = 0;
    jobs[i].rawCap = 0;
    jobs[i].compressed = NULL;
    jobs[i].compressedLen = 0;
    jobs[i].compressedCap = 0;
    jobs[i].decompressed = NULL;
    jobs[i].decompressedLen = 0;
    jobs[i].decompressedCap = 0;
    jobs[i].compressorCycles = 0;
    jobs[i].decompressorCycles = 0;
    jobs[i].compressorStallCycles = 0;
    jobs[i].decompressorStallCycles = 0;
  }
  
  summary.totalSize = 0;
  summary.totalPages = 0;
  summary.nonzeroSize = 0;
  summary.nonzeroPages = 0;
  summary.passedPages = 0;
  summary.failedPages = 0;
  summary.compressorCycles = 0;
  summary.compressorStalls = 0;
  summary.decompressorCycles = 0;
  summary.decompressorStalls = 0;
  
  // assert reset on rising edge to initialize module state
  compressor->reset = 1;
  compressor->clock = 0;
  compressor->eval();
  COMPRESSOR_TRACE();
  compressor->clock = 1;
  compressor->eval();
  compressor->reset = 0;
  
  decompressor->reset = 1;
  decompressor->clock = 0;
  decompressor->eval();
  DECOMPRESSOR_TRACE();
  decompressor->clock = 1;
  decompressor->eval();
  decompressor->reset = 0;
  
  quit = false;
  while(!quit && !isFinished()) {
    doLoad();
    doCompressor();
    doDecompressor();
    doFinalize();
  }
  
  fprintf(reportfile, "***** FINISHED *****\n");
  fprintf(reportfile, "dumps: %s\n", options.dump);
  fprintf(reportfile, "total (bytes): %lu\n", summary.totalSize);
  fprintf(reportfile, "total (pages): %d\n", summary.totalPages);
  fprintf(reportfile, "non-zero (bytes): %lu\n", summary.nonzeroSize);
  fprintf(reportfile, "non-zero (pages): %d\n", summary.nonzeroPages);
  fprintf(reportfile, "passed (pages): %d\n", summary.passedPages);
  fprintf(reportfile, "failed (pages): %d\n", summary.failedPages);
  fprintf(reportfile, "pass rate: %f\n", (double)summary.passedPages / summary.nonzeroPages);
  fprintf(reportfile, "compressed (bits): %lu\n", summary.compressedSize);
  fprintf(reportfile, "compression ratio: %f\n", (double)summary.nonzeroSize / summary.compressedSize * 8);
  fprintf(reportfile, "C-cycles: %d\n", summary.compressorCycles);
  fprintf(reportfile, "C-throughput (B/c): %f\n", (double)summary.nonzeroSize / summary.compressorCycles);
  fprintf(reportfile, "D-cycles: %d\n", summary.decompressorCycles);
  fprintf(reportfile, "D-throughput (B/c): %f\n", (double)summary.nonzeroSize / summary.decompressorCycles);
  
  cleanup();
  
  return min(summary.failedPages, 255);
}

static bool doLoad() {
  static int jobIdx = 0;
  struct Job *job = &jobs[jobIdx];
  if(job->stage != STAGE_LOAD)
    return false;
  if(job->raw == NULL) {
    job->raw = (uint8_t*)malloc(PAGE_SIZE);
    assert(job->raw != NULL);
    job->rawCap = PAGE_SIZE;
  }
  
  size_t bytesRead =
    fread(job->raw + job->rawLen, 1, PAGE_SIZE - job->rawLen, dumpfile);
  job->rawLen += bytesRead;
  
  if(job->rawLen == PAGE_SIZE || feof(dumpfile)) {
    // finished loading page
    bool zero = true;
    for(int i = 0; i < job->rawLen; i++)
      zero = zero && job->raw[i] == 0;
    if(job->rawLen == 0) {
      job->stage = STAGE_FINISH;
    }
    else if(zero) {
      summary.totalPages += 1;
      summary.totalSize += job->rawLen;
      
      job->rawLen = 0;
    }
    else {
      job->id = summary.nonzeroPages;
      
      summary.totalPages += 1;
      summary.totalSize += job->rawLen;
      
      summary.nonzeroPages += 1;
      summary.nonzeroSize += job->rawLen;
      
      job->stage++;
      jobIdx = ++jobIdx % JOB_QUEUE_SIZE;
    }
  }
  
  return true;
}

static bool doCompressor() {
  static int jobIdxIn = 0;
  static int jobIdxOut = 0;
  static int inBufIdx = 0;
  struct Job *jobIn = &jobs[jobIdxIn];
  struct Job *jobOut = &jobs[jobIdxOut];
  bool quit = false;
  int idle = 0;
  bool onlyOut = jobIn->stage == STAGE_FINISH;
  if(jobIn->stage != STAGE_COMPRESSOR &&
      (!onlyOut || jobOut->stage != STAGE_COMPRESSOR)) {
    return false;
  }
  
  do {
    if(jobOut->compressedLen + HUFFMAN_COMPRESSOR_BITS_OUT > jobOut->compressedCap*8) {
      size_t newSize = max(jobOut->compressedCap * 2, PAGE_SIZE);
      while(jobOut->compressedLen + HUFFMAN_COMPRESSOR_BITS_OUT > newSize*8)
        newSize *= 2;
      uint8_t *oldBuf = jobOut->compressed;
      jobOut->compressed = (uint8_t*)realloc(jobOut->compressed, newSize);
      assert(jobOut->compressed != NULL);
      jobOut->compressedCap = newSize;
    }
    
    // expose input buffer to module
    int remaining = jobIn->rawLen - inBufIdx;
    compressor->io_in_valid = min(remaining, HUFFMAN_COMPRESSOR_CHARS_IN);
    compressor->io_in_last = remaining <= HUFFMAN_COMPRESSOR_CHARS_IN;
    // module input is not in array form, so must use an ugly cast
    if(!onlyOut) // prevent segfault
    for(int i = 0; i < compressor->io_in_valid; i++) {
      (&compressor->io_in_data_0)[i] = jobIn->raw[inBufIdx + i];
    }
    if(onlyOut) {
      compressor->io_in_valid = 0;
      compressor->io_in_last = false;
    }
    
    compressor->io_out_ready = HUFFMAN_COMPRESSOR_BITS_OUT;
    compressor->io_out_restart = false;
    
    // update outputs based on new inputs
    compressor->eval();
    
    idle++;
    
    // shift input buffer by number of characters consumed by module input
    size_t c = min(compressor->io_in_valid, compressor->io_in_ready);
    if(c) idle = 0;
    inBufIdx += c;
    
    // push module output onto the end of output buffer
    c = min(compressor->io_out_valid, compressor->io_out_ready);
    // if(c) idle = 0;
    // module output is not in array form, so must use ugly cast
    for(int i = 0; i < c; i++) {
      int major = (jobOut->compressedLen + i) / 8;
      int minor = (jobOut->compressedLen + i) % 8;
      jobOut->compressed[major] &= (1 << minor) - 1;
      jobOut->compressed[major] |= !!(&compressor->io_out_data_0)[i] << minor;
    }
    jobOut->compressedLen += c;
    
    compressor->io_out_restart = compressor->io_out_last &&
      compressor->io_out_ready >= compressor->io_out_valid;
    compressor->eval();
    
    for(int i = jobIdxOut;;i = ++i % JOB_QUEUE_SIZE) {
      jobs[i].compressorCycles++;
      if(i == jobIdxIn) break;
    }
    summary.compressorCycles += 1;
    
    if(compressor->io_in_restart) {
      jobIdxIn = ++jobIdxIn % JOB_QUEUE_SIZE;
      inBufIdx = 0;
      jobIn = &jobs[jobIdxIn];
      quit = quit || jobIn->stage != STAGE_COMPRESSOR;
    }
    if(compressor->io_out_restart) {
      jobIdxOut = ++jobIdxOut % JOB_QUEUE_SIZE;
      jobOut->stage++;
      jobOut = &jobs[jobIdxOut];
      quit = quit || (onlyOut && jobOut->stage != STAGE_COMPRESSOR);
    }
    
    
    // make ure everything is still up to date
    compressor->eval();
    COMPRESSOR_TRACE();
    
    // prepare for rising edge
    compressor->clock = 0;
    compressor->eval();
    COMPRESSOR_TRACE();
    
    // update module registers with rising edge
    compressor->clock = 1;
    compressor->eval();
    
    if(TIMEOUT)
      cleanup();
    assert(!TIMEOUT);
  } while(!quit);
  
  return true;
}

static bool doDecompressor() {
  static int jobIdxIn = 0;
  static int jobIdxOut = 0;
  static int inBufIdx = 0;
  struct Job *jobIn = &jobs[jobIdxIn];
  struct Job *jobOut = &jobs[jobIdxOut];
  bool quit = false;
  int idle = 0;
  bool onlyOut = jobIn->stage == STAGE_FINISH;
  if(jobIn->stage != STAGE_DECOMPRESSOR &&
      (!onlyOut || jobOut->stage != STAGE_DECOMPRESSOR)) {
    return false;
  }
  
  do {
    if(jobOut->decompressedLen + HUFFMAN_DECOMPRESSOR_CHARS_OUT >
        jobOut->decompressedCap) {
      size_t newSize = max(jobOut->decompressedCap * 2, PAGE_SIZE);
      while(jobOut->decompressedLen + HUFFMAN_DECOMPRESSOR_CHARS_OUT > newSize)
        newSize *= 2;
      uint8_t *oldBuf = jobOut->decompressed;
      jobOut->decompressed = (uint8_t*)realloc(jobOut->decompressed, newSize);
      assert(jobOut->compressed != NULL);
      jobOut->decompressedCap = newSize;
    }
    
    // expose input buffer to module
    int remaining = jobIn->compressedLen - inBufIdx;
    decompressor->io_in_valid = min(remaining, HUFFMAN_DECOMPRESSOR_BITS_IN);
    decompressor->io_in_last = remaining <= HUFFMAN_DECOMPRESSOR_BITS_IN;
    // module input is not in array form, so must use an ugly cast
    if(!onlyOut)
    for(int i = 0; i < decompressor->io_in_valid; i++) {
      int major = (inBufIdx + i) / 8;
      int minor = (inBufIdx + i) % 8;
      (&decompressor->io_in_data_0)[i] = jobIn->compressed[major] >> minor & 1;
    }
    if(onlyOut) {
      decompressor->io_in_valid = 0;
      decompressor->io_in_last = false;
    }
    
    decompressor->io_out_ready = HUFFMAN_DECOMPRESSOR_CHARS_OUT;
    decompressor->io_out_restart = false;
    
    // update outputs based on new inputs
    decompressor->eval();
    
    idle++;
    
    // shift input buffer by number of characters consumed by module input
    size_t c = min(decompressor->io_in_valid, decompressor->io_in_ready);
    if(c) idle = 0;
    inBufIdx += c;
    
    // push module output onto the end of output buffer
    c = min(decompressor->io_out_valid, decompressor->io_out_ready);
    // if(c) idle = 0;
    // module output is not in array form, so must use ugly cast
    for(int i = 0; i < c; i++) {
      jobOut->decompressed[jobOut->decompressedLen + i] =
        (&decompressor->io_out_data_0)[i];
    }
    jobOut->decompressedLen += c;
    
    decompressor->io_out_restart = decompressor->io_out_last &&
      decompressor->io_out_ready >= decompressor->io_out_valid;
    decompressor->eval();
    
    
    for(int i = jobIdxOut;;i = ++i % JOB_QUEUE_SIZE) {
      jobs[i].decompressorCycles++;
      if(i == jobIdxIn) break;
    }
    summary.decompressorCycles += 1;
    
    if(decompressor->io_in_restart) {
      jobIdxIn = ++jobIdxIn % JOB_QUEUE_SIZE;
      inBufIdx = 0;
      jobIn = &jobs[jobIdxIn];
      quit = quit || jobIn->stage != STAGE_DECOMPRESSOR;
    }
    if(decompressor->io_out_restart) {
      jobIdxOut = ++jobIdxOut % JOB_QUEUE_SIZE;
      jobOut->stage++;
      jobOut = &jobs[jobIdxOut];
      quit = quit || (onlyOut && jobOut->stage != STAGE_DECOMPRESSOR);
    }
    
    
    DECOMPRESSOR_TRACE();
    
    // prepare for rising edge
    decompressor->clock = 0;
    decompressor->eval();
    DECOMPRESSOR_TRACE();
    
    // update module registers with rising edge
    decompressor->clock = 1;
    decompressor->eval();
    
    if(TIMEOUT)
      cleanup();
    assert(!TIMEOUT);
  } while(!quit);
  
  return true;
}

static bool doFinalize() {
  static int jobIdx = 0;
  struct Job *job = &jobs[jobIdx];
  if(job->stage != STAGE_FINALIZE)
    return false;
  
  bool pass = true;
  if(job->rawLen != job->decompressedLen)
    pass = false;
  else
  for(int i = 0; i < job->rawLen; i++) {
    pass = pass && job->raw[i] == job->decompressed[i];
  }
  if(pass)
    summary.passedPages += 1;
  else
    summary.failedPages += 1;
  
  summary.compressedSize += job->compressedLen;
  
  static bool printHeader = true;
  if(printHeader) {
    printHeader = false;
    fprintf(reportfile, "dump,");
    fprintf(reportfile, "id,");
    fprintf(reportfile, "pass?,");
    fprintf(reportfile, "raw size,");
    fprintf(reportfile, "compressed size,");
    fprintf(reportfile, "cycles in compressor,");
    fprintf(reportfile, "cycles in decompressor,");
    fprintf(reportfile, "\n");
  }
  
  fprintf(reportfile, "%s,", options.dump);
  fprintf(reportfile, "%d,", job->id);
  fprintf(reportfile, "%s,", pass ? "pass" : "fail");
  fprintf(reportfile, "%lu,", job->rawLen);
  fprintf(reportfile, "%lu,", job->compressedLen);
  fprintf(reportfile, "%d,", job->compressorCycles);
  fprintf(reportfile, "%d,", job->decompressorCycles);
  fprintf(reportfile, "\n");
  
  if(job->id == debugJobId) {
    fprintf(reportfile, "\n");
    fprintf(reportfile, "====================\n");
    fprintf(reportfile, "| BEGIN DEBUG DUMP |\n");
    fprintf(reportfile, "====================\n");
    fprintf(reportfile, "job ID: %d\n", job->id);
    fprintf(reportfile, "raw: (length = %lu)\n", job->rawLen);
    for(size_t i = 0; i < job->rawLen; i +=
      fwrite(job->raw + i, 1, job->rawLen - i, reportfile));
    fprintf(reportfile, "\n");
    fprintf(reportfile, "\n");
    fprintf(reportfile, "compressed: (length = %lu)\n", job->compressedLen);
    for(size_t i = 0; i * 8 < job->compressedLen; i += 8 *
      fwrite(job->compressed + i, 1, (job->compressedLen+7)/8 - i, reportfile));
    fprintf(reportfile, "\n");
    fprintf(reportfile, "\n");
    fprintf(reportfile, "decompressed: (length = %lu)\n", job->decompressedLen);
    for(size_t i = 0; i < job->decompressedLen; i +=
      fwrite(job->decompressed + i, 1, job->decompressedLen - i, reportfile));
    fprintf(reportfile, "\n");
    fprintf(reportfile, "\n");
    fprintf(reportfile, "====================\n");
    fprintf(reportfile, "|  END DEBUG DUMP  |\n");
    fprintf(reportfile, "====================\n");
    fprintf(reportfile, "\n");
  }
  
  job->stage = 0;
  job->rawLen = 0;
  job->compressedLen = 0;
  job->decompressedLen = 0;
  job->compressorCycles = 0;
  job->decompressorCycles = 0;
  job->compressorStallCycles = 0;
  job->decompressorStallCycles = 0;
  
  jobIdx = ++jobIdx % JOB_QUEUE_SIZE;
  
  return true;
}
