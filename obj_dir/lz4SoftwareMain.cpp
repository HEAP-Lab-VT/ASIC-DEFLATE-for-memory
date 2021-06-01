#include "../lz4/lib/lz4.h"
#include "../lz4/lib/lz4.c"
#include <sys/stat.h>
#include <fstream>
#include <iostream>
#define PAGE_SIZE 4096 // number of bytes in a page
#define LZ4_OUTPUT_SIZE PAGE_SIZE*2
#define BITS_PER_CHARACTER 8 // This refers to a character in C++, not a character of the compressor or decompressor.
#define CHARACTER_BITS 9 // This refers to the number of bits in a character of the compressor and decompressor.

// Enabling this allows the output, byte-extended to 4KB, to be output to a file 
#define FLUSH_OUTPUT true
// This is the number of pages into the file that the page to be dumped is located. So if you want to compress and dump
// The 46th page, the number would be 45.
#define FLUSH_PAGE_NUMBER 71
// This is the name of the file to be flushed to.
#define FLUSH_FILE_NAME "lz4FlushFile.dump"

int main(int argc, char **argv, char **env)
{
	struct stat fileStats;
	size_t fileBytes;
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
	char lz4OutputArray[LZ4_OUTPUT_SIZE];
	std::ifstream myFile(fileName, std::ifstream::in | std::ifstream::binary);
	size_t uncompressedSize = 0;
	size_t compressedSize = 0;

	for (size_t loopIndex = 0; loopIndex < fileBytes/PAGE_SIZE; loopIndex++)
	{
		if (fileBytes >= PAGE_SIZE)
		{
			myFile.read(inputArray, PAGE_SIZE);
		}
		else{
			printf("Error, file cannot be read fully\n");
			return 1; 
		}
		bool allSameByte = true;
		char compareByte = inputArray[0];
		for(size_t allSameByteCheckIndex = 0; allSameByteCheckIndex < PAGE_SIZE; allSameByteCheckIndex++){
			if(inputArray[allSameByteCheckIndex] != compareByte){
				allSameByte = false;
				break;
			}
		}
		int lz4ReturnValue;
		if(!allSameByte){
			lz4ReturnValue = LZ4_compress_fast(inputArray, lz4OutputArray, PAGE_SIZE, LZ4_OUTPUT_SIZE, 1);
			if(lz4ReturnValue <= 0){
				printf("Error, lz4 return value error, exiting.\n");
				return 1;
			}
			uncompressedSize += PAGE_SIZE;
			compressedSize += lz4ReturnValue;
		}
		//printf("%d:i=o:lz4=%d,\n", loopIndex, lz4ReturnValue);
		std::cout << std::flush;

#if FLUSH_OUTPUT
		if(loopIndex == FLUSH_PAGE_NUMBER){
			std::ofstream fout;
			fout.open(FLUSH_FILE_NAME, std::ofstream::binary | std::ofstream::out);
			for(size_t flushIndex = 0; flushIndex < PAGE_SIZE; flushIndex++){
				fout.write(&lz4OutputArray[flushIndex], sizeof(char));
			}

			fout.close();
			printf("The lz4 flushed output is %d bytes\n", lz4ReturnValue);
			exit(0);
		}
#endif
	}
	printf(argv[0]);
	printf("\n");
	printf("File compression ratio is: %f\n", (uncompressedSize*1.0)/(compressedSize*1.0));
	exit(0);
}
