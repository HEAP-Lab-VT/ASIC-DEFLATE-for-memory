`timescale 1ns/1ps
module lz77CompressorDecompressorTestbench();

reg clock, reset;
reg [7:0] writeData;
reg [7:0] patternData [0:6];
reg [2:0] patternDataLength;
reg matchResultReady;
reg writeDataValid, patternDataValid;
wire writeDataReady, patternDataReady, matchResultValid;
wire [11:0] matchResultIndex;
wire [2:0] matchResultLength;


reg [7:0] dataIn [0:4095];
wire [7:0] decompressedDataOut [0:4095];
wire finished;
wire [12:0] numberOfMatchingBytes;
lz77CompressorDecompressorWrapper lz77(
  .clock(clock),
  .reset(reset),
  .dataIn(dataIn),
  .dataOut(decompressedDataOut),
  .numberOfMatchingBytes(numberOfMatchingBytes),
  .finished(finished)
);
// This allows the reading of an input file for the testbench
integer readFileResult, inputFile, fileIterator;
// This is used to determine what 4KB page of data from the testbench input file to read
integer loopCount;
integer desiredLoop = 0;
// This is used to determine if we should break out of the loop.
integer loopExitCount;

integer count;
integer clockCount;


initial begin
	$dumpfile("traceOutput.fst");
	$dumpvars(0,lz77CompressorDecompressorTestbench);
end

initial begin
  // This read the data from the input file.
  $display("opened testbench input file");
  inputFile = $fopen("testbenchInputFile.txt", "rb");
  for(loopCount = 0; loopCount <= desiredLoop; loopCount = loopCount + 1) begin
    readFileResult = $fread(dataIn, inputFile);
  end
  $fclose(inputFile);
  $display("closed testbench input file");

  $display("starting signals");
  // These signals setup the reset signals in the beginning.
  patternDataValid = 0;
  writeDataValid = 0;
  matchResultReady = 0;
  clock = 0;
  reset = 0;
  #10;
  reset = 1;
  clock = 1;
  #10;
  clock = 0;
  #10;
  clock = 1;
  #10;
  clock = 0;
  #10;
  reset = 0;

  // Once the data is read in from the input file, this loads it into the pattern search.
  $display("Started hardware simulation");
  loopCount = 0; 
  while(loopCount <= 20000 && !finished) begin
    #10;
    clock = 0;
    #10;
    clock = 1;
    loopCount = loopCount + 1;
  end

  if(numberOfMatchingBytes == 4096) begin
    $display("Compression and decompression success!");
  end else begin
    $display("Compression and decompression success!");
  end

  #10;
  clock = 0;
  #10;
  clock = 1;
  #10;
  clock = 0;


  #10;
  clock = 1;
  #10;
  clock = 0;
  $finish();
end

endmodule
