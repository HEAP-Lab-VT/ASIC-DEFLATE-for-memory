#include "VhuffmanCompressorDecompressorWrapper.h"
#include "verilated.h"
#include <sys/stat.h>
#include <fstream>
#include <iostream>
#include "lzwModified.cpp"
#define PAGE_SIZE 4096 // number of bytes in a page
#define LZ4_OUTPUT_SIZE PAGE_SIZE * 2
#define BITS_PER_CHARACTER 8 // This refers to a character in C++, not a character of the compressor or decompressor.
#define TIMEOUT_ENABLE true
#define TIMEOUT_CYCLES 80000
#define CHECK_OUTPUTS true

int main(int argc, char **argv, char **env)
{
	Verilated::commandArgs(argc, argv);
	VhuffmanCompressorDecompressorWrapper *top = new VhuffmanCompressorDecompressorWrapper;
	struct stat fileStats;
	unsigned int fileBytes;
	char *fileName = argv[argc - 1];

	if (stat(fileName, &fileStats) == 0)
	{
		fileBytes = fileStats.st_size;
		printf("File is %d bytes\n", fileBytes);
	}
	else
	{
		printf("Failed to get file statistics, exitting now\n");
		return 1;
	}

	char inputArray[PAGE_SIZE];
	char lzwModifiedOutputArray[LZ4_OUTPUT_SIZE];
	char huffmanDecompressedArray[PAGE_SIZE];
	char lzwModifiedDecompressedArray[PAGE_SIZE];
	std::ifstream myFile(fileName, std::ifstream::in | std::ifstream::binary);

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

		int lzwModifiedReturnValue = modifiedLZWCompress(inputArray, lzwModifiedOutputArray);
		if (lzwModifiedReturnValue <= 0)
		{
			printf("lzwModifiedReturnValue error, exiting\n");
			return 1;
		}

		top->start = 0;
		top->reset = 0;
		top->clock = 0;

		// If the lzwModified output is larger than the input, just use the original input for huffman.
		if (lzwModifiedReturnValue < PAGE_SIZE)
		{
			for (size_t index = 0; index < PAGE_SIZE; index++)
			{
				top->dataIn[index] = lzwModifiedOutputArray[index];
			}
			top->compressionLimit = lzwModifiedReturnValue;
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
		top->reset = 0;
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
#if CHECK_OUTPUTS
		for (size_t index = 0; index < PAGE_SIZE; index++)
		{
			huffmanDecompressedArray[index] = (char)top->dataOut[index];
		}
		// decompress lzwModified
		if (lzwModifiedReturnValue < PAGE_SIZE)
		{
			modifiedLZWDecompress(huffmanDecompressedArray, lzwModifiedDecompressedArray);
		}
		else
		{
			for (size_t index = 0; index < PAGE_SIZE; index++)
			{
				lzwModifiedDecompressedArray[index] = huffmanDecompressedArray[index];
			}
		}
		for (size_t index = 0; index < PAGE_SIZE; index++)
		{
			if (lzwModifiedDecompressedArray[index] != inputArray[index])
			{
				printf("index %d %d!=%d", index, (char)lzwModifiedDecompressedArray[index], (char)inputArray[index]);
				equal = false;
			}
		}
#endif
		if (equal)
		{
			printf("%d:i=o:lzwModified=%d:huffman=%d,\n", loopIndex, lzwModifiedReturnValue, top->outputBytes);
			std::cout << std::flush;
		}
		else
		{
			printf("loop %d of file %s: ", loopIndex, fileName);
			printf("input does not equal output\n");
		}
	}
	printf(argv[0]);
	printf("\n");
	delete top;
	exit(0);
}
