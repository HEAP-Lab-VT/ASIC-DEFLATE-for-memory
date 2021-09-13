#include <cstddef>
#include <cstdio>
#include <sys/stat.h>
#include <fstream>
#include <iostream>
#include <time.h>

// This is the number of characters in the input data.
#define CHARACTERS 4096
// This is the number of characters in the compression output array.
#define OUTPUT_CHARACTERS CHARACTERS * 2
// This is the number of bits in a character.
#define CHARACTER_BITS 8
// This is how many possible bits in the worst-case output of the compressor
#define MAXIMUM_COMPRESSED_BITS OUTPUT_CHARACTERS *CHARACTER_BITS
// This is the character that is used as a token that tells when a sequence of characters is going to be shown.
#define TOKEN 103
// This is the bit that's used to show that the TOKEN is not being repeated twice, which would indicate that there is no sequence.
#define SEQUENCE_BIT 1 ^ (TOKEN >> 7)
// This is the minimum number of bytes in a sequence that need to be matched for a sequence to be used instead of the literals.
#define MINIMUM_SEQUENCE_CHARACTERS 4
// This is the number of characters that can be represented in a sequence without adding another byte of data to the sequence format.
#define THREE_BYTE_MAXIMUM_SEQUENCE_CHARACTERS 11
// This is the maximum number of bytes in a character sequence.
#define MAXIMUM_SEQUENCE_CHARACTERS 266
// This is the number of address bits used to tell the address of the start of a character sequence. The address bits refer to the distance the address is away from the current location.
#define SEQUENCE_ADDRESS_BITS 12
// This is the number of bits used to tell the length of a character sequence.
#define SEQUENCE_LENGTH_BITS 3
// This is the number of bits used to describe a character sequence.
//      bits breakdown:              TOKEN|SEQUENCE BIT|SEQUENCE_LENGTH_BITS|SEQUENCE_ADDRESS_BITS
#define CHARACTER_SEQUENCE_BITS CHARACTER_BITS + SEQUENCE_BIT + SEQUENCE_LENGTH_BITS + SEQUENCE_ADDRESS_BITS

// This allows lzwModified to be used as a library when desired by commenting out the main() function
#define USE_AS_LIBRARY false

// This allows lzwModified to output the compressed form of the input data to a file.
#define OUTPUT_TO_FILE false

// This allows lzwModified to only match the first X characters initially, choose the first longest match, then only investigate that match to see if the match was longer.
#define CAP_INITIAL_MATCH false
#define INITIAL_MATCH_CAP_LENGTH 16

#define DEBUG false

struct sequenceMatch
{
    bool matchFound;
    size_t address;
    size_t length;

    bool operator==(const sequenceMatch &a) const
    {
        return matchFound == a.matchFound && address == a.address && length == a.length;
    }

    bool operator!=(const sequenceMatch &a) const
    {
        return !(*this == a);
    }
};

// This returns how many characters in a row of the inputs match.
size_t checkMatch(char *data1, char *data2, size_t maxCharactersToCheck)
{
    size_t matchLength = 0;

    for (size_t index = 0; index < maxCharactersToCheck; index++)
    {
        if (data1[index] == data2[index])
        {
            matchLength++;
        }
        else
        {
            break;
        }
    }
    return matchLength;
}

// This function goes through a range of the input data looking for the longest match of a character sequence length, then returns the index of the starting character of the match
struct sequenceMatch findCharacterSequence(char *inputData, size_t charactersToSearch, char *currentCharacterSequence, size_t charactersInSequence)
{
    struct sequenceMatch result;
    result.matchFound = false;

    //printf("characters in sequence: %d\n", charactersInSequence);
    if (charactersInSequence > 0)
    {
        //printf("characters to search: %d\n", charactersToSearch);
        for (size_t characterIndex = 0; characterIndex < charactersToSearch; characterIndex++)
        {
            //printf("characters in sequence: %d\n", charactersInSequence);
            size_t matchLength;
            if (charactersToSearch - characterIndex > charactersInSequence)
            {
                matchLength = checkMatch(&inputData[characterIndex], currentCharacterSequence, charactersInSequence);
            }
            else
            {
                matchLength = checkMatch(&inputData[characterIndex], currentCharacterSequence, charactersToSearch - characterIndex);
            }

            if (matchLength >= MINIMUM_SEQUENCE_CHARACTERS)
            {
                if (!result.matchFound)
                {
                    result.address = characterIndex;
                    result.length = matchLength;
                    result.matchFound = true;
                }
#if CAP_INITIAL_MATCH
                else if (matchLength > result.length && result.length < INITIAL_MATCH_CAP_LENGTH)
#else
                else if (matchLength > result.length)
#endif
                {
                    result.address = characterIndex;
                    result.length = matchLength;
                }
            }
        }
    }
    //printf("finding character sequence, found?%d, address:%d, length:%d\n", result.matchFound, result.address, result.length);

    return result;
}

// This function adds a single bit to the array of bools for compression
size_t addBitToArray(bool *compressedBits, size_t currentPosition, bool bit)
{
    compressedBits[currentPosition] = bit;
    return currentPosition + 1;
}

// This function takes in a number of bits and a pointer to the current place in an array of bools, inserts the new bits into the array, and outputs
// the most up to date position in the array.
size_t addBitsToArray(bool *compressedBits, size_t currentPosition, size_t numberOfBits, size_t bits)
{
    //printf("adding %d bits: %d\n", numberOfBits, bits);
    size_t newCurrentPosition = currentPosition;
    for (size_t index = 0; index < numberOfBits; index++)
    {
        newCurrentPosition = addBitToArray(compressedBits, newCurrentPosition, (1) & (bits >> (numberOfBits - 1 - index)));
    }
    return newCurrentPosition;
}

// This function takes in a char and adds it to the compressed bits array.
size_t addLiteralByteToArray(bool *compressedBits, size_t currentPosition, char byte)
{
    if (byte == (char)TOKEN)
    {
        currentPosition = addBitsToArray(compressedBits, currentPosition, CHARACTER_BITS, byte);
        return addBitsToArray(compressedBits, currentPosition, CHARACTER_BITS, byte);
    }
    else
    {
        return addBitsToArray(compressedBits, currentPosition, CHARACTER_BITS, byte);
    }
}

size_t addSequenceToArray(bool *compressedBits, size_t currentPosition, struct sequenceMatch match)
{
    size_t newCurrentPosition = currentPosition;

    newCurrentPosition = addBitsToArray(compressedBits, newCurrentPosition, CHARACTER_BITS, TOKEN);
    newCurrentPosition = addBitToArray(compressedBits, newCurrentPosition, SEQUENCE_BIT);
#if THREE_BYTE_MAXIMUM_SEQUENCE_CHARACTERS != MAXIMUM_SEQUENCE_CHARACTERS
    if (match.length >= THREE_BYTE_MAXIMUM_SEQUENCE_CHARACTERS)
    {
        // This needs a longer sequence format.
        newCurrentPosition = addBitsToArray(compressedBits, newCurrentPosition, SEQUENCE_LENGTH_BITS, THREE_BYTE_MAXIMUM_SEQUENCE_CHARACTERS - MINIMUM_SEQUENCE_CHARACTERS);
        newCurrentPosition = addBitsToArray(compressedBits, newCurrentPosition, SEQUENCE_ADDRESS_BITS, match.address);
        newCurrentPosition = addBitsToArray(compressedBits, newCurrentPosition, CHARACTER_BITS, match.length - THREE_BYTE_MAXIMUM_SEQUENCE_CHARACTERS);
    }
    else
    {
#endif
        // This only uses the standard 3-byte sequence format, because there are enough bits to encode how many bytes of data are in the sequence.
        newCurrentPosition = addBitsToArray(compressedBits, newCurrentPosition, SEQUENCE_LENGTH_BITS, match.length - MINIMUM_SEQUENCE_CHARACTERS);
        newCurrentPosition = addBitsToArray(compressedBits, newCurrentPosition, SEQUENCE_ADDRESS_BITS, match.address);
#if THREE_BYTE_MAXIMUM_SEQUENCE_CHARACTERS != MAXIMUM_SEQUENCE_CHARACTERS
    }
#endif
    return newCurrentPosition;
}

int modifiedLZWCompress(char *inputData, char *outputData)
{
    // This is the buffer that stores the characters being compared against the previous history
    char currentCharacterSequence[MAXIMUM_SEQUENCE_CHARACTERS];
    // This stores how many characters are in the current character buffer.
    size_t charactersInSequence = 0;
    // This is where the compression output will be stored.
    bool compressedBits[MAXIMUM_COMPRESSED_BITS];
    size_t compressedBitsIndex = 0;
    // This is the index of the last longest match in the history.
    struct sequenceMatch lastLongestMatch;
    lastLongestMatch.matchFound = false;

    for (size_t characterIndex = 0; characterIndex < CHARACTERS; characterIndex++)
    {
#if DEBUG
        printf("characterIndex = %d\n", characterIndex);
#endif
        if (charactersInSequence < MINIMUM_SEQUENCE_CHARACTERS)
        {
            // The character buffer isn't long enough for any meaningful matches, so add the next character and finish this iteration.
            currentCharacterSequence[charactersInSequence] = inputData[characterIndex];
            lastLongestMatch.matchFound = false;
            charactersInSequence++;
        }
        else
        {
            currentCharacterSequence[charactersInSequence] = inputData[characterIndex];
            charactersInSequence++;
#if DEBUG
            printf("startcharactersearch\n");
#endif
            struct sequenceMatch newMatchResult = findCharacterSequence(inputData, characterIndex - charactersInSequence + 1, currentCharacterSequence, charactersInSequence);
#if DEBUG
            printf("endcharactersearch\n");
#endif
            if (newMatchResult.matchFound)
            {
                if (!lastLongestMatch.matchFound || lastLongestMatch.length < newMatchResult.length)
                {
                    lastLongestMatch = newMatchResult;
                }

                if (charactersInSequence == MAXIMUM_SEQUENCE_CHARACTERS || lastLongestMatch.length < charactersInSequence)
                {
                    // The longest match has been found, output the data and fix the character buffer accordingly.
                    // Add the token
                    compressedBitsIndex = addSequenceToArray(compressedBits, compressedBitsIndex, lastLongestMatch);
                    if (charactersInSequence == lastLongestMatch.length)
                    {
                        charactersInSequence = 0;
                    }
                    else
                    {
                        for (size_t iteration = 0; iteration < lastLongestMatch.length; iteration++)
                        {
                            for (size_t index = 0; index < charactersInSequence; index++)
                            {
                                currentCharacterSequence[index] = currentCharacterSequence[index + 1];
                            }
                        }
                        charactersInSequence -= lastLongestMatch.length;
                    }
                }
            }
            else
            {
                // No match found, so need to shift new data into the character sequence buffer.
                compressedBitsIndex = addLiteralByteToArray(compressedBits, compressedBitsIndex, currentCharacterSequence[0]);
                charactersInSequence--;
                for (size_t index = 0; index < charactersInSequence; index++)
                {
                    currentCharacterSequence[index] = currentCharacterSequence[index + 1];
                }
            }
        }
    }

    // If the buffer still has some characters in it, this is a quick way to push them all out.
    for (size_t index = 0; index < charactersInSequence; index++)
    {
        compressedBitsIndex = addLiteralByteToArray(compressedBits, compressedBitsIndex, currentCharacterSequence[index]);
    }

    size_t outArrayIndex = 0;
    // This converts the bit array into a char array.
    for (size_t index = 0; index < compressedBitsIndex / CHARACTER_BITS; index++)
    {
        char nextByte = 0;
        for (size_t bitIndex = 0; bitIndex < CHARACTER_BITS; bitIndex++)
        {
            nextByte = (nextByte << 1) | compressedBits[index * CHARACTER_BITS + bitIndex];
        }
        //printf("compressedOutput: %d\n", nextByte);
        outputData[outArrayIndex] = nextByte;
        outArrayIndex++;
    }

    return outArrayIndex + 1;
}

void modifiedLZWDecompress(char *inputData, char *outputData)
{
    size_t inputIndex = 0;
    size_t outputIndex = 0;
    while (outputIndex < CHARACTERS)
    {
        if (inputData[inputIndex] == TOKEN)
        {
            // THe input character is the token.
            if (inputData[inputIndex + 1] == TOKEN)
            {
                // The character after that is a token as well, so this is just a literal token.
                outputData[outputIndex] = TOKEN;
                inputIndex += 2;
                outputIndex++;
            }
            else
            {
                // The character after is not a token, so begin decoding a character sequence.
                size_t charactersToCopy = 4 + ((inputData[inputIndex + 1] >> (CHARACTER_BITS / 2)) & 7);
                size_t startingCopyIndex = ((inputData[inputIndex + 1] << CHARACTER_BITS) | (unsigned char)inputData[inputIndex + 2]) & 0xfff;
#if THREE_BYTE_MAXIMUM_SEQUENCE_CHARACTERS != MAXIMUM_SEQUENCE_CHARACTERS
                if (charactersToCopy >= THREE_BYTE_MAXIMUM_SEQUENCE_CHARACTERS)
                {
                    // There is an additional byte at the end of the character sequence.
                    charactersToCopy = THREE_BYTE_MAXIMUM_SEQUENCE_CHARACTERS + (unsigned char)inputData[inputIndex + 3];
                    inputIndex += 4;
                }
                else
                {
#endif
                    inputIndex += 3;
#if THREE_BYTE_MAXIMUM_SEQUENCE_CHARACTERS != MAXIMUM_SEQUENCE_CHARACTERS
                }
#endif
                for (size_t copyIndex = 0; copyIndex < charactersToCopy; copyIndex++)
                {
                    outputData[outputIndex + copyIndex] = outputData[startingCopyIndex + copyIndex];
                }
                outputIndex += charactersToCopy;
            }
        }
        else
        {
            // This is just a single normal character, so just output it like normal.
            outputData[outputIndex] = inputData[inputIndex];
            inputIndex++;
            outputIndex++;
        }
    }
}
#if !USE_AS_LIBRARY
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
    char inputArray[CHARACTERS];
    char lzwOutputArray[OUTPUT_CHARACTERS];
    char outputArray[CHARACTERS];
    std::ifstream myFile(fileName, std::ios::in | std::ios::binary);
    size_t totalCompressedSize = 0;
    size_t totalUncompressedSize = 0;

    for (int loopIndex = 0; loopIndex < fileBytes / CHARACTERS; loopIndex++)
    {
        if (fileBytes >= CHARACTERS)
        {
            myFile.read(inputArray, CHARACTERS);
        }
        else
        {
            printf("Error, file cannot be read fully\n");
        }
        totalUncompressedSize += CHARACTERS;
        size_t compressedPageSize = modifiedLZWCompress(inputArray, lzwOutputArray);
#if DEBUG
        modifiedLZWDecompress(lzwOutputArray, outputArray);
        for (size_t index = 0; index < CHARACTERS; index++)
        {
            //printf("index: %d, compressed: 0x%x, uncompressed: %d\n", index, testOutputArray[index], testDecompressedArray[index]);
            if (inputArray[index] != outputArray[index])
            {
                printf("character %d was not the same as the input. Input data was %d, but output data was %d\n", index, inputArray[index], outputArray[index]);
            }
        }
#endif

        totalCompressedSize += compressedPageSize;
        printf("compressed size = %d\n", compressedPageSize);
#if OUTPUT_TO_FILE
        std::ofstream outputFile;
        outputFile.open("lzwModifiedOutput.dump", std::ios::out | std::ios::binary);
        if (outputFile.is_open())
        {
            for (size_t index = 0; index < OUTPUT_CHARACTERS; index++)
            {
                outputFile << lzwOutputArray[index];
            }
            outputFile.close();
            printf("lzwModified output has been dumped.\n");
        }
#endif
    }

    return 0;
}
#endif
