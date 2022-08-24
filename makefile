
root = $(shell pwd)
export root
build ?= build
override build := $(abspath $(build))
export build
test ?= $(build)/test
override test := $(abspath $(test))
export test

TRACE ?= 
export TRACE

src := $(root)/src/main/scala
srcedu := $(src)/edu/vt/cs/hardware_compressor
utilsrc := $(shell find $(srcedu)/util)
lzsources := $(shell find $(srcedu)/lz  -not \( -path $(srcedu)/lz/test -prune \)) $(utilsrc) $(root)/configFiles/lz.csv $(root)/build.sbt
huffmansources := $(shell find $(srcedu)/huffman $(src)/huffman -not \( \( -path $(src)/huffman/buffers -o -path $(src)/huffman/wrappers \) -prune \) ) $(utilsrc) $(root)/configFiles/huffman.csv $(root)/build.sbt
deflatesources := $(shell find $(srcedu)/deflate) $(utilsrc) $(lzsources) $(huffmansources) $(root)/configFiles/deflate.csv $(root)/build.sbt
cppsrc := $(root)/verilator
export cppsrc
lzccppsources := $(cppsrc)/DecoupledStreamModule.cpp
lzdcppsources := $(cppsrc)/DecoupledStreamModule.cpp
huffmancppsources := $(cppsrc)/Huffman_fused.cpp $(cppsrc)/BitQueue.c
deflatecppsources := $(cppsrc)/Deflate_fused.cpp $(cppsrc)/BitQueue.c
lzccppheaders := 
lzdcppheaders := 
huffmancppheaders := $(cppsrc)/BitQueue.h
deflatecppheaders := $(cppsrc)/BitQueue.h
lzcflags := '-DMODNAME=LZCompressor -DIN_CHARS=11 -DOUT_CHARS=8'
lzdflags := '-DMODNAME=LZDecompressor -DIN_CHARS=8 -DOUT_CHARS=8'
huffmanflags := 
deflateflags := 

_SBTFLAGS := $(SBTFLAGS)
_SBTFLAGS += --batch
_SBTFLAGS += -Dsbt.server.forcestart=true # workaround for SBT bug
_SBTFLAGS += -Dsbt.color=always

_SBTRUNFLAGS := $(SBTRUNFLAGS)

export _SBTFLAGS
export _SBTRUNFLAGS


all: lz huffman deflate

lz: lz.v Vlz
lz.v: LZCompressor.v LZDecompressor.v
Vlz: VLZCompressor VLZDecompressor

huffman: huffman.v Vhuffman
huffman.v: HuffmanCompressor.v HuffmanDecompressor.v
Vhuffman: VHuffman_fused

deflate: deflate.v Vdeflate
deflate.v: DeflateCompressor.v DeflateDecompressor.v
Vdeflate: VDeflate_fused

clean:
	rm -rf $(build)

.PHONY: all lz lz.v Vlz huffman huffman.v Vhuffman deflate deflate.v Vdeflate clean

TARGET_FILES := \
	LZCompressor.v \
	LZDecompressor.v \
	HuffmanCompressor.v \
	HuffmanDecompressor.v \
	DeflateCompressor.v \
	DeflateDecompressor.v \
	VHuffmanTest \
	VDeflateTest \
	VLZCompressor \
	VLZDecompressor \
	VHuffman_fused \
	VDeflate_fused

.PHONY: $(TARGET_FILES)
$(TARGET_FILES): %: $(build)/%



$(build)/LZCompressor.v: $(lzsources)
	sbt $(_SBTFLAGS) 'runMain edu.vt.cs.hardware_compressor.lz.LZCompressor -td $(build) -o $@ $(_SBTRUNFLAGS)'
$(build)/LZDecompressor.v: $(lzsources)
	sbt $(_SBTFLAGS) 'runMain edu.vt.cs.hardware_compressor.lz.LZDecompressor -td $(build) -o $@ $(_SBTRUNFLAGS)'
$(build)/HuffmanCompressor.v: $(huffmansources)
	sbt $(_SBTFLAGS) 'runMain edu.vt.cs.hardware_compressor.huffman.HuffmanCompressor -td $(build) -o $@ $(_SBTRUNFLAGS)'
$(build)/HuffmanDecompressor.v: $(huffmansources)
	sbt $(_SBTFLAGS) 'runMain edu.vt.cs.hardware_compressor.huffman.HuffmanDecompressor -td $(build) -o $@ $(_SBTRUNFLAGS)'
$(build)/DeflateCompressor.v: $(deflatesources)
	sbt $(_SBTFLAGS) 'runMain edu.vt.cs.hardware_compressor.deflate.DeflateCompressor -td $(build) -o $@ $(_SBTRUNFLAGS)'
$(build)/DeflateDecompressor.v: $(deflatesources)
	sbt $(_SBTFLAGS) 'runMain edu.vt.cs.hardware_compressor.deflate.DeflateDecompressor -td $(build) -o $@ $(_SBTRUNFLAGS)'


$(build)/VHuffmanTest: $(build)/vlmakefile $(build)/HuffmanCompressor.v $(build)/HuffmanDecompressor.v
	$(MAKE) -C $(build) -f vlmakefile VHuffmanTest TARGET=VHuffmanTest MODULES='HuffmanCompressor HuffmanDecompressor' OBJS='HuffmanTest.o'

$(build)/VDeflateTest: $(build)/vlmakefile $(build)/DeflateCompressor.v $(build)/DeflateDecompressor.v
	$(MAKE) -C $(build) -f vlmakefile VDeflateTest TARGET=VDeflateTest MODULES='DeflateCompressor DeflateDecompressor' OBJS='DeflateTest.o'

$(build)/VLZCompressor: $(build)/vlmakefile $(build)/LZCompressor.v
	$(MAKE) -C $(build) -f vlmakefile VLZCompressor TARGET=VLZCompressor MODULES=LZCompressor OBJS='LZCompressor.o'

$(build)/VLZDecompressor: $(build)/vlmakefile $(build)/LZDecompressor.v
	$(MAKE) -C $(build) -f vlmakefile VLZDecompressor TARGET=VLZDecompressor MODULES=LZDecompressor OBJS='LZDecompressor.o'

$(build)/VHuffman_fused: $(build)/vlmakefile $(build)/HuffmanCompressor.v $(build)/HuffmanDecompressor.v
	$(MAKE) -C $(build) -f vlmakefile VHuffman_fused TARGET=VHuffman_fused MODULES='HuffmanCompressor HuffmanDecompressor' OBJS='Huffman_fused.o BitQueue.o'

$(build)/VDeflate_fused: $(build)/vlmakefile $(build)/DeflateCompressor.v $(build)/DeflateDecompressor.v
	$(MAKE) -C $(build) -f vlmakefile VDeflate_fused TARGET=VDeflate_fused MODULES='DeflateCompressor DeflateDecompressor' OBJS='Deflate_fused.o BitQueue.o'

.PHONY: $(build)/VHuffmanTest $(build)/VDeflateTest $(build)/VLZCompressor $(build)/VLZDecompressor $(build)/VHuffman_fused $(build)/VDeflate_fused


$(build)/vlmakefile: vlmakefile $(build)
	cp vlmakefile $(build)/vlmakefile
$(build):
	mkdir -p $(build)


$(test)/testmakefile: testmakefile $(test)
	cp testmakefile $(test)/testmakefile
$(test):
	mkdir -p $(test)

.PHONY: test-deflate test-huffman

BENCHMARKS ?= $(wildcard ../benchmarks/test/*)
override BENCHMARKS := $(abspath $(BENCHMARKS))
export BENCHMARKS

$(test)/test-deflate-report.txt: $(test)/testmakefile $(build)/VDeflateTest $(BENCHMARKS)
	$(MAKE) -C $(test) -f testmakefile $@

test-deflate: $(test)/test-deflate-report.txt
