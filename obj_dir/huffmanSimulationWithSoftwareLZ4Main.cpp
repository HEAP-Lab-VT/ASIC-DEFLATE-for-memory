#include "VhuffmanCompressorDecompressorWrapper.h"
#include "../lz4/lib/lz4.h"
#include "../lz4/lib/lz4.c"
#include "verilated.h"
#include <sys/stat.h>
#include <fstream>
#include <iostream>
#define PAGE_SIZE 4096 // number of bytes in a page
#define LZ4_OUTPUT_SIZE PAGE_SIZE * 2
#define BITS_PER_CHARACTER 8 // This refers to a character in C++, not a character of the compressor or decompressor.
#define CHARACTER_BITS 9		 // This refers to the number of bits in a character of the compressor and decompressor.
#define TIMEOUT_ENABLE true
#define TIMEOUT_CYCLES 80000

int huffmanHardwareWithLZ4(char* inputArray, char* outputArray){
	char lz4OutputArray[LZ4_OUTPUT_SIZE];
	char huffmanDecompressedArray[PAGE_SIZE];
	Verilated::commandArgs(argc, argv);
	VhuffmanCompressorDecompressorWrapper *top = new VhuffmanCompressorDecompressorWrapper;
	int lz4ReturnValue = LZ4_compress_fast(inputArray, lz4OutputArray, PAGE_SIZE, LZ4_OUTPUT_SIZE, 1);
	if(lz4ReturnValue <= 0){
		printf("lz4ReturnValue error, exiting\n");
		return 1;
	}

	top->start = 0;
	top->reset = 0;
	top->clock = 0;

	for (size_t loopIndex = 0; loopIndex < fileBytes / PAGE_SIZE; loopIndex++)
	{
		if (fileBytes >= PAGE_SIZE)
		{
			myFile.read(inputArray, PAGE_SIZE);
		}
		else
		{
			printf("Error, file cannot be read fully\n");
		}

		int lz4ReturnValue = LZ4_compress_fast(inputArray, lz4OutputArray, PAGE_SIZE, LZ4_OUTPUT_SIZE, 1);
		if (lz4ReturnValue <= 0)
		{
			printf("lz4ReturnValue error, exiting\n");
			return 1;
		}
	}
	top->eval();
	top->reset = 1;
	top->eval();
	top->clock = 1;
	top->eval();
	top->clock = 0;
	top->eval();
	top->clock = 1;
	top->eval();
	top->clock = 0;
	top->eval();
	top->reset = 0;
	top->clock = 1;
	top->eval();
	top->clock = 0;
	top->eval();

		top->start = 0;
		top->reset = 0;
		top->clock = 0;

		// If the lz4 output is larger than the input, just use the original input for huffman.
		if (lz4ReturnValue < PAGE_SIZE)
		{
			for (size_t index = 0; index < PAGE_SIZE; index++)
			{
				top->dataIn[index] = lz4OutputArray[index];
			}
			top->compressionLimit = lz4ReturnValue;
		}
		else
		{
			for (size_t index = 0; index < PAGE_SIZE; index++)
			{
				top->dataIn[index] = inputArray[index];
			}
			top->compressionLimit = PAGE_SIZE;
		}
		top->eval();
		top->reset = 1;
		top->eval();
		top->clock = 1;
		top->eval();
		top->clock = 0;
		top->eval();
		top->clock = 1;
		top->eval();
		top->clock = 0;
		top->eval();
		top->clock = 1;
		top->eval();
		top->clock = 0;
		top->eval();

		top->start = 1;

		bool lastFinished = true;
		long long counter = 0;

		while (!(top->finished && !lastFinished) || counter > 500000)
		{
			counter++;
			lastFinished = top->finished;
			top->eval();
			top->clock = 0;
			top->eval();
			top->clock = 1;
#if TIMEOUT_ENABLE
			if (counter > TIMEOUT_CYCLES)
			{
				printf("Timeout error, too many cycles\n");
				return -1;
			}
#endif
		}
		//printf("%d\n", counter);
		bool equal = true;

		for (size_t index = 0; index < PAGE_SIZE; index++)
		{
			huffmanDecompressedArray[index] = (char)top->dataOut[index];
		}
		// decompress lz4
		if (lz4ReturnValue < PAGE_SIZE)
		{
			LZ4_decompress_safe(huffmanDecompressedArray, lz4DecompressedArray, lz4ReturnValue, PAGE_SIZE);
		}
		else
		{
			for (size_t index = 0; index < PAGE_SIZE; index++)
			{
				lz4DecompressedArray[index] = huffmanDecompressedArray[index];
			}
		}

		for (size_t index = 0; index < PAGE_SIZE; index++)
		{
			if (lz4DecompressedArray[index] != inputArray[index])
			{
				printf("index %d %d!=%d", index, (char)lz4DecompressedArray[index], (char)inputArray[index]);
				equal = false;
			}
		}
		if (equal)
		{
			printf("%d:i=o:lz4=%d:huffman=%d,\n", loopIndex, lz4ReturnValue, top->outputBytes);
			std::cout << std::flush;
		}
	}

	for (size_t index = 0; index < PAGE_SIZE; index++)
	{
		if (outputArray[index] != inputArray[index])
		{
			equal = false;
		}
	}
	
	if(equal){
		return top->outputBytes;
	}
	else{
		return -1;
	}

	delete top;
}