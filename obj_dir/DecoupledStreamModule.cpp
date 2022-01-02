#include "verilated.h"
#include <stdio.h>

// <editor-fold> ugly pre-processor macros
#define _STR(s) #s
#define STR(s) _STR(s)
#define _CAT(s,t) s##t
#define CAT(s,t) _CAT(s,t)
#define REPEAT(n,x) do{_CAT(REPEAT,n)(x)}while(0)
#define REPEAT0(x)
#define REPEAT1(x) REPEAT0(x) x(0)
#define REPEAT2(x) REPEAT1(x) x(1)
#define REPEAT3(x) REPEAT2(x) x(2)
#define REPEAT4(x) REPEAT3(x) x(3)
#define REPEAT5(x) REPEAT4(x) x(4)
#define REPEAT6(x) REPEAT5(x) x(5)
#define REPEAT7(x) REPEAT6(x) x(6)
#define REPEAT8(x) REPEAT7(x) x(7)
#define REPEAT9(x) REPEAT8(x) x(8)
#define REPEAT10(x) REPEAT9(x) x(9)
#define REPEAT11(x) REPEAT10(x) x(10)
#define REPEAT12(x) REPEAT11(x) x(11)
#define REPEAT13(x) REPEAT12(x) x(12)
#define REPEAT14(x) REPEAT13(x) x(13)
#define REPEAT15(x) REPEAT14(x) x(14)
#define REPEAT16(x) REPEAT15(x) x(15)
#define REPEAT17(x) REPEAT16(x) x(16)
#define REPEAT18(x) REPEAT17(x) x(17)
#define REPEAT19(x) REPEAT18(x) x(18)
#define REPEAT20(x) REPEAT19(x) x(19)
#define REPEAT21(x) REPEAT20(x) x(20)
#define REPEAT22(x) REPEAT21(x) x(21)
#define REPEAT23(x) REPEAT22(x) x(22)
#define REPEAT24(x) REPEAT23(x) x(23)
#define REPEAT25(x) REPEAT24(x) x(24)
#define REPEAT26(x) REPEAT25(x) x(25)
#define REPEAT27(x) REPEAT26(x) x(26)
#define REPEAT28(x) REPEAT27(x) x(27)
#define REPEAT29(x) REPEAT28(x) x(28)
#define REPEAT30(x) REPEAT29(x) x(29)
#define REPEAT31(x) REPEAT30(x) x(30)
#define REPEAT32(x) REPEAT31(x) x(31)
#define REPEAT33(x) REPEAT32(x) x(32)
#define REPEAT34(x) REPEAT33(x) x(33)
#define REPEAT35(x) REPEAT34(x) x(34)
#define REPEAT36(x) REPEAT35(x) x(35)
#define REPEAT37(x) REPEAT36(x) x(36)
#define REPEAT38(x) REPEAT37(x) x(37)
#define REPEAT39(x) REPEAT38(x) x(38)
#define REPEAT40(x) REPEAT39(x) x(39)
#define REPEAT41(x) REPEAT40(x) x(40)
#define REPEAT42(x) REPEAT41(x) x(41)
#define REPEAT43(x) REPEAT42(x) x(42)
#define REPEAT44(x) REPEAT43(x) x(43)
#define REPEAT45(x) REPEAT44(x) x(44)
#define REPEAT46(x) REPEAT45(x) x(45)
#define REPEAT47(x) REPEAT46(x) x(46)
#define REPEAT48(x) REPEAT47(x) x(47)
#define REPEAT49(x) REPEAT48(x) x(48)
#define REPEAT50(x) REPEAT49(x) x(49)
// </editor-fold>

#ifndef MODNAME
// #define MODNAME LZ77Compressor
#endif
#define VMODNAME CAT(V,MODNAME)
#include STR(VMODNAME.h)

#ifndef TRACE_ENABLE
#define TRACE_ENABLE false
#endif

#if TRACE_ENABLE
#include "verilated_vcd_c.h"
#endif

#ifndef IN_CHARS
#define IN_CHARS 8
#endif
#ifndef OUT_CHARS
#define OUT_CHARS 8
#endif

#ifndef YUQING_MODE
#define YUQING_MODE false
#endif

#ifndef TIMEOUT
#define TIMEOUT (idle >= 1000)
#endif

static size_t min(size_t a, size_t b) {return a <= b ? a : b;}
static size_t max(size_t a, size_t b) {return a >= b ? a : b;}


#if !YUQING_MODE
int main(int argc, char **argv, char **env)
#else
int MODNAME(const char *input, size_t inlen, char *output, size_t *outlen,
	const char *tracefile)
#endif
{
	Verilated::commandArgs(argc, argv);
	VMODNAME *module = new VMODNAME;
	
#if TRACE_ENABLE
#if !YUQING_MODE
	char trace_enable = argc > 3 && (argv[3][0] != '-' || argv[3][1] != '\0');
#else
	char trace_enable = tracefile != NULL && tracefile[0] != '\0' &&
		(tracefile[0] != '-' || tracefile[1] != '\0');
#endif
	VerilatedVcdC* trace;
	if(trace_enable) {
		Verilated::traceEverOn(true);
		trace = new VerilatedVcdC;
		module->trace(trace, 99);
#if !YUQING_MODE
		trace->open(argv[3]);
#else
		trace->open(tracefile);
#endif
	}
#endif
	
#if !YUQING_MODE
	FILE *inf = stdin;
	FILE *outf = stdout;
	if(argc > 1 && (argv[1][0] != '-' || argv[1][1] != '\0'))
		inf = fopen(argv[1], "r");
	if(argc > 2 && (argv[2][0] != '-' || argv[2][1] != '\0'))
		outf = fopen(argv[2], "w");
#else
	size_t outbuflen = *outlen;
	*outlen = 0;
#endif
	
	char inBuf[IN_CHARS];
	size_t inBufIdx = 0;
	char outBuf[OUT_CHARS];
	size_t outBufIdx = 0;
	
	// assert reset on rising edge to initialize module state
	module->reset = 1;
	module->clock = 0;
	module->eval();
	module->clock = 1;
	module->eval();
	module->reset = 0;
	module->io_in_finished = 0;
	
	int cycles = 0;
	int idle = 0;
	do {
		// update module registers with rising edge
		module->clock = 1;
		module->eval();
		
		// read bytes from input stream to input buffer
#if !YUQING_MODE
		size_t bytesRead = fread(inBuf + inBufIdx, 1, IN_CHARS - inBufIdx, inf);
		module->io_in_finished = module->io_in_finished || feof(inf);
		inBufIdx += bytesRead;
#else
		while(true) {
			if(!inlen) {
				module->io_in_finished = true;
				break;
			}
			else if(IN_CHARS == inBufIdx) {
				break;
			}
			*(inBuf + inBufIdx) = *input;
			inBufIdx++;
			input++;
			inlen--;
		}
#endif
		
		// expose input buffer to module
		// module input is not in array form, so must convert
		#define action(n) module->io_in_data_##n = inBuf[n];
		REPEAT(IN_CHARS, action);
		
		// assert valid input count and ready output count
		module->io_in_valid = inBufIdx;
		module->io_out_ready = OUT_CHARS - outBufIdx;
		
		// update module outputs based on new inputs
		module->eval();
		
		idle++;
		
		// shift input buffer by number of characters consumed by module input
		size_t c = min(module->io_in_valid, module->io_in_ready);
		if(c) idle = 0;
		inBufIdx -= c;
		for(int i = 0; i < inBufIdx; i++)
			inBuf[i] = inBuf[i + c];
		
		// read module output to temporary buffer
		// module output is not in array form, so must convert
		char tmpBuf[OUT_CHARS];
		#undef action
		#define action(n) tmpBuf[n] = module->io_out_data_##n;
		REPEAT(OUT_CHARS, action);
		
		// push module output onto the end of output buffer
		c = min(module->io_out_valid, module->io_out_ready);
		if(c) idle = 0;
		for(int i = 0; i < c; i++)
			outBuf[i + outBufIdx] = tmpBuf[i];
		outBufIdx += c;
		
		// write output buffer to output stream
#if !YUQING_MODE
		c = fwrite(outBuf, 1, outBufIdx, outf);
#else
		for(c = 0; c < outBufIdx && *outlen < outbuflen; c++) {
			output[outlen] = outBuf[c];
			*outlen++;
		}
		c = outBufIdx;
#endif
		for(int i = c; i < outBufIdx; i++)
			outBuf[i + c] = outBuf[i];
		outBufIdx -= c;
		
#if TRACE_ENABLE
		// put all the important information in the trace graph
		if(trace_enable) {
			trace->dump(Verilated::time());
			Verilated::timeInc(1);
		}
#endif
		
		// prepare for next rising edge
		module->clock = 0;
		module->eval();
		
#if TRACE_ENABLE
		// show clock oscillation in the trace graph
		if(trace_enable) {
			trace->dump(Verilated::time());
			Verilated::timeInc(1);
		}
#endif
		
		cycles++;
	} while((!module->io_out_finished || outBufIdx)
		&& !TIMEOUT);

#if !YUQING_MODE
	if(inf != stdin)
		fclose(inf);
	if(outf != stdout)
		fclose(outf);
#endif
	
#if TRACE_ENABLE
		if(trace_enable) {
			trace->close();
		}
#endif
	
	delete module;
	
#if !YUQING_MODE
	if(!TIMEOUT)
		fprintf(stderr, "cycles: %d\n", cycles);
	else
		fprintf(stderr, "cycles: %d (timeout)\n", cycles);
	
	return 0;
#else
	if(!TIMEOUT)
		return cycles;
	else
		return -1;
#endif
}
