/*
 * Copyright 2011 Jeanfrancois Arcand
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
// $Id: Deflater.java 122 2007-08-18 08:25:04Z pornin $
/*
 * Copyright (c) 2007  Thomas Pornin
 * 
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 * 
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 * ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.atmosphere.gwt.server.deflate;

import java.io.IOException;
import java.io.OutputStream;

/**
 * This class implements the core DEFLATE process, i.e. the compression of data into DEFLATE blocks.
 * 
 * @version $Revision: 122 $
 * @author Thomas Pornin
 */

public final class Deflater {
	
	/*
	 * In this class, all state variables and arrays have been designed such that the default values (all-zero) are
	 * fine.
	 */

	/* =========================================================== */
	/*
	 * Input data mangement.
	 * 
	 * The window keeps a copy of the previously encountered uncompressed data bytes. In the beginning, the window is
	 * empty, then filled up to a certain limit (a power of 2, at most 32768). Beyond that limit, it is managed as a
	 * circular buffer.
	 * 
	 * To each data byte we associate a triplet, consisting of that byte and the next two bytes. We assemble triplets
	 * into 24-bit values, such that the lower 8 bits are the most recently received. Each window slot contains a
	 * triplet; hence, each input data byte appears in three distinct, successive slots in the window.
	 * 
	 * Window triplets are linked as hash chains, with the hashLink[] array. The hashTable[] array contains the chain
	 * entry points.
	 * 
	 * We also copy input bytes into the ucBuffer[] array (up to some limit). These are used to produce uncompressed
	 * data blocks if it turns out that the compressor cannot find anything better. The size limit is computed so that
	 * when an uncompressed data block must be produced, then the size limit on ucBuffer[] has not been reached. Note:
	 * technically, we could limit ucBuffer[] to the window length and complete the uncompressed block by reconstructing
	 * the input bytes from the symbols in buffer[]. This would be interesting if we used a large buffer[] array. But
	 * such a large symbol buffer is undesirable since it prevents the dynamic Huffman codes from adapting to changes in
	 * the file content type.
	 */

	/**
	 * The window buffer. Entry of index "n" contains the triplet of bytes beginning at index "n".
	 */
	private int[] window;
	
	/**
	 * <code>windowPtr</code> contains the window index where the next triplet will go.
	 */
	private int windowPtr;
	
	/**
	 * This state variable is initially 0; it is set to 1 after the first data byte has been received, and 2 once the
	 * second data byte has been received.
	 */
	private int windowState;
	
	/**
	 * <code>recentBytes</code> is a 16-bit value which contains the last two received bytes.
	 */
	private int recentBytes;
	
	/**
	 * Hash chains: element "n" in this array contains the backward distance to the previous triplet with the same hash
	 * (or 0, if there is none). Due to the window buffer reuse, the previous triplet may have been overwritten: the
	 * code which walks a chain must make sure that it stays within the window size.
	 */
	private char[] windowLink;
	
	/**
	 * For the hash value "v", <code>hashTable[v]</code> contains the value "j". If <code>j == 0</code>, then there is
	 * no current chain for this hash value; otherwise, the chain head is in the window, at index "j-1". It may happen
	 * that the chain head entry has been overwritten: the insertion code checks the designated entry when linking a new
	 * triplet.
	 */
	private char[] hashTable;
	
	/**
	 * The buffer which receives a copy of the uncompressed data. That buffer is circular.
	 */
	private byte[] ucBuffer;
	
	/**
	 * The length of the accumulated data in <code>ucBuffer</code>.
	 */
	private int ucBufferPtr;
	
	/**
	 * The current to-match sequence length. If its value is 0, 1 or 2, then we do not have a triplet yet and there is
	 * no match. Otherwise, with a value of 3 or more, than there is a match with a previous sequence which begins at
	 * index <code>seqPtr</code> and distance <code>seqDist</code>.
	 */
	private int seqLen;
	
	/**
	 * The index at which the current match begins (ignored if <code>seqLen</code> is 2 or less).
	 */
	private int seqPtr;
	
	/**
	 * The distance between the current matched sequence and its source. Ignored if <code>seqLen</code> is 2 or less.
	 */
	private int seqDist;
	
	/* =========================================================== */

	/*
	 * Compression parameters.
	 * 
	 * These parameters alter both the compression ratio and the compression speed.
	 */

	/**
	 * The maximum hash chain explored length when looking for a triplet.
	 */
	private int maxChainLengthTriplet;
	
	/**
	 * The maximum backward distance when looking for a triplet.
	 */
	private int maxDistanceTriplet;
	
	/**
	 * The maximum hash chain explored length when looking for a previous longer sequence.
	 */
	private int maxChainLengthSeq1;
	
	/**
	 * The maximum backward distance when looking for a previous longer sequence.
	 */
	private int maxDistanceSeq1;
	
	/**
	 * If the current sequence exceeds this length, then the explored chain length when looking for a previous longer
	 * distance is reduced (divided by four).
	 */
	private int goodSequenceLength;
	
	/**
	 * If the current sequence exceeds this length, then a lazy match is not attempted.
	 */
	private int maxLazyLength;
	
	/**
	 * The maximum hash chain explored length when looking for a sequence in lazy match mode.
	 */
	private int maxChainLengthSeq2;
	
	/**
	 * The maximum backward distance when looking for a sequence in lazy match mode.
	 */
	private int maxDistanceSeq2;
	
	/**
	 * If <code>true</code>, then no LZ77 sharing will be performed. Compression will come only from the Huffman codes.
	 */
	private boolean huffOnly;
	
	/* =========================================================== */
	/*
	 * Output data management.
	 * 
	 * Output symbols are accumulated in a buffer. Each entry is a 32-bit word consisting of: -- literal value in the
	 * literal+length alphabet (9 bits) -- extra length bits (5 bits) -- distance symbol (5 bits) -- extra distance
	 * length (13 bits) (values ordered from least- to most-significant bits)
	 * 
	 * If the literal value is not a sequence copy symbol (i.e. it has value 255 or less) then the other fields are
	 * zero. Note that the end-of-block special literal (value 256) is never written in that buffer.
	 * 
	 * The lengths of the buffer[] and ucBuffer[] arrays are linked. Basically, if there are bufferLen symbols in
	 * buffer[], then these could be encoded as (at worst) 32bufferLen bits, in a type 1 block (plus the three-bit block
	 * header). Hence, a type 0 block may be used only if the uncompressed data length is no more than 32bufferLen-32
	 * bits, because this is the payload length for an uncompressed block of size 32bufferLen bits (then again excluding
	 * the three-bit header, and the additional byte-alignment padding bits). Therefore, ucBuffer.length needs not be
	 * larger than 4buffer.length (we need 258 more extra bytes to accomodate a currently matched sequence).
	 * 
	 * We furthermore limit buffer.length to 16384, thus guaranteeing that when type 0 blocks are selected, no more than
	 * 65532 bytes are to be written out, which fits in a single block. The drawback is that uncompressible data may
	 * fill buffer[] after only 16384 literal bytes, which yields an overhead four times larger than the optimal
	 * overhead. This is not a serious issue: it turns out that zlib has the same overhead and is quite happy with it.
	 * 
	 * Actual writes to the output stream use an internal buffer so that unbuffered transport streams can be used.
	 * Compression is inherently a buffered process.
	 */

	/**
	 * The output buffer for symbols.
	 */
	private int[] buffer;
	
	/**
	 * The number of symbols currently accumulated in the buffer.
	 */
	private int bufferPtr;
	
	/**
	 * The underlying transport stream for compressed data.
	 */
	private OutputStream out;
	
	/**
	 * This byte value accumulates up to 7 bits, to be sent as part of the next complete byte.
	 */
	private int outByte;
	
	/**
	 * The number of accumulated bits in <code>outByte</code> (between 0 and 7, inclusive).
	 */
	private int outPtr;
	
	/**
	 * This buffer is used internally to accumulate compressed data bytes before sending them to the transport stream.
	 */
	private byte[] outBuf;
	
	/**
	 * This pointer value marks the current limit to the data stored in <code>outBuf[]</code>.
	 */
	private int outBufPtr;
	
	/**
	 * This flag is set to <code>true</code> when processing a dictionary.
	 */
	private boolean noOutput;
	
	/* =========================================================== */

	/**
	 * Compression level 1: do not apply LZ77; only Huffman codes are computed. This is faster than <code>SPEED</code>
	 * but with a degraded compression ratio.
	 */
	public static final int HUFF = 1;
	
	/**
	 * Compression level 2: optimize for speed.
	 */
	public static final int SPEED = 2;
	
	/**
	 * Compression level 3: compromise between speed and compression ratio. This is the default level.
	 */
	public static final int MEDIUM = 3;
	
	/**
	 * Compression level 4: try to achieve best compression ratio, possibly at the expense of compression speed. This
	 * level may occasionaly yield slightly worse results than the <code>MEDIUM</code> level.
	 */
	public static final int COMPACT = 4;
	
	/**
	 * Build a deflater with the default parameters (<code>MEDIUM</code> level, 15-bit window).
	 */
	public Deflater() {
		this(0, 15);
	}
	
	/**
	 * Build a deflater with the provided compression strategy (either <code>SPEED</code>, <code>MEDIUM</code> or
	 * <code>COMPACT</code>). A standard 15-bit window is used. If the compression level is 0, then the default
	 * compression level is selected.
	 * 
	 * @param level
	 *            the compression strategy
	 */
	public Deflater(int level) {
		this(level, 15);
	}
	
	/**
	 * Build a deflater with the provided compression strategy (either <code>SPEED</code>, <code>MEDIUM</code> or
	 * <code>COMPACT</code>) and the provided window size. The window size is expressed in bits and may range from 9 to
	 * 15 (inclusive). The DEFLATE format uses a 15-bit window; by using a smaller window, the produced stream can be
	 * inflated with less memory (but compression ratio is lowered). If the compression level is 0, then the default
	 * compression level is selected.
	 * 
	 * @param level
	 *            the compression strategy
	 * @param windowBits
	 *            the window bit size (9 to 15)
	 */
	public Deflater(int level, int windowBits) {
		if (windowBits < 9 || windowBits > 15)
			throw new IllegalArgumentException("invalid LZ77 window bit length: " + windowBits);
		
		/*
		 * The internal buffer should not be too large, because it prevents the Huffman codes from adapting to data
		 * internal changes. When the internal buffer is too small, the block headers and dynamic tree representations
		 * become too expensive. The maximum value is 16384; to use something greater, the type 0 block code in
		 * endBlock() would have to be adapted.
		 */
		int bufferLen = 16384;
		
		int windowLen = 1 << windowBits;
		window = new int[windowLen];
		windowLink = new char[windowLen];
		hashTable = new char[windowLen];
		maxDistanceTriplet = windowLen - 261;
		maxDistanceSeq1 = windowLen - 261;
		maxDistanceSeq2 = windowLen - 261;
		if (level == 0)
			level = MEDIUM;
		switch (level) {
		case SPEED:
			maxChainLengthTriplet = 16;
			maxChainLengthSeq1 = 8;
			goodSequenceLength = 8;
			maxLazyLength = 4;
			maxChainLengthSeq2 = 4;
			break;
		case MEDIUM:
			maxChainLengthTriplet = 128;
			maxChainLengthSeq1 = 128;
			goodSequenceLength = 64;
			maxLazyLength = 64;
			maxChainLengthSeq2 = 32;
			break;
		case COMPACT:
			maxChainLengthTriplet = 1024;
			maxChainLengthSeq1 = 1024;
			goodSequenceLength = 258;
			maxLazyLength = 258;
			maxChainLengthSeq2 = 1024;
			break;
		case HUFF:
			huffOnly = true;
			break;
		default:
			throw new IllegalArgumentException("unknown compression level: " + level);
		}
		buffer = new int[bufferLen];
		ucBuffer = new byte[4 * bufferLen + 258];
		outBuf = new byte[4096];
	}
	
	/**
	 * Get the current output transport stream.
	 * 
	 * @return the current output stream
	 */
	public OutputStream getOut() {
		return out;
	}
	
	/**
	 * Set the current output transport stream. This method must be called before compressing data.
	 * 
	 * @param out
	 *            the new transport stream
	 */
	public void setOut(OutputStream out) {
		this.out = out;
	}
	
	/* =========================================================== */
	/*
	 * LZ77 implementation.
	 */

	/**
	 * Compute a 32-bit copy symbol, for the provided sequence length and source distance.
	 * 
	 * @param len
	 *            the sequence length
	 * @param dist
	 *            the source sequence distance
	 * @return the copy symbol
	 */
	private static int makeCopySymbol(int len, int dist) {
		int symlen, elen;
		if (len <= 10) {
			symlen = 254 + len;
			elen = 0;
		}
		else if (len == 258) {
			symlen = 285;
			elen = 0;
		}
		else {
			/*
			 * This may be optimized with a dichotomic search.
			 */
			int i;
			for (i = 9; i < 29; i++)
				if (LENGTH[i] > len)
					break;
			symlen = 256 + i;
			elen = len - LENGTH[i - 1];
		}
		
		int symdist, edist;
		if (dist <= 4) {
			symdist = dist - 1;
			edist = 0;
		}
		else {
			/*
			 * This may be optimized with a dichotomic search.
			 */
			int i;
			for (i = 5; i < 30; i++)
				if (DIST[i] > dist)
					break;
			symdist = i - 1;
			edist = dist - DIST[i - 1];
		}
		
		return symlen + (elen << 9) + (symdist << 14) + (edist << 19);
	}
	
	/**
	 * Find a previous sequence, identical to the sequence at index <code>orig</code> and length <code>len</code>, such
	 * that this previous sequence is continued by three bytes equal to the provided <code>end</code> value.
	 * <strong>IMPORTANT:</strong> the "original" sequence must actually be longer than <code>len</code> by two bytes,
	 * which match the high two bytes of <code>end</code>.
	 * 
	 * @param win
	 *            the window buffer
	 * @param winMask
	 *            the window buffer length, minus one
	 * @param wlink
	 *            the window link buffer
	 * @param orig
	 *            the original sequence begin index
	 * @param dist
	 *            the distance associated with the original sequence
	 * @param len
	 *            the original sequence length
	 * @param end
	 *            the sequence end triplet
	 * @param maxLen
	 *            the maximum explored chain length
	 * @param maxDist
	 *            the maximum accepted distance
	 * @return the previous longer sequence distance, or 0 if not found
	 */
	private static int findPreviousSequence(int[] win, int winMask, char[] wlink, int orig, int dist, int len, int end, int maxLen, int maxDist) {
		int n = orig;
		int chainLength = maxLen;
		loop: while (chainLength-- > 0) {
			int d = wlink[n];
			if (d == 0)
				return 0;
			dist += d;
			if (dist > maxDist)
				return 0;
			n = (n - d) & winMask;
			int tm = len;
			int j = orig, k = n;
			if (tm >= 3) {
				while (tm >= 3) {
					if (win[j] != win[k])
						continue loop;
					tm -= 3;
					j = (j + 3) & winMask;
					k = (k + 3) & winMask;
				}
				switch (tm) {
				case 1:
					j = (j - 2) & winMask;
					k = (k - 2) & winMask;
					if (win[j] != win[k])
						continue loop;
					k = (k + 3) & winMask;
					break;
				case 2:
					j = (j - 1) & winMask;
					k = (k - 1) & winMask;
					if (win[j] != win[k])
						continue loop;
					k = (k + 3) & winMask;
					break;
				}
			}
			else {
				if (win[j] != win[k])
					continue loop;
				k = (k + tm) & winMask;
			}
			if (win[k] == end)
				return dist;
		}
		return 0;
	}
	
	/**
	 * Update the <code>ucBuffer</code> array with the provided uncompressed data. This must be done before ending the
	 * block.
	 * 
	 * @param buf
	 *            the source data buffer
	 * @param off1
	 *            the source start offset (inclusive)
	 * @param off2
	 *            the source end offset (exclusive)
	 */
	private void updateUCBuffer(byte[] buf, int off1, int off2) {
		int uLen = ucBuffer.length;
		int sInc = (uLen >>> 1);
		while (off1 < off2) {
			int free = uLen - ucBufferPtr;
			int tc = off2 - off1;
			if (tc > sInc)
				tc = sInc;
			if (tc > free) {
				System.arraycopy(buf, off1, ucBuffer, ucBufferPtr, free);
				System.arraycopy(buf, off1 + free, ucBuffer, 0, tc - free);
				ucBufferPtr = tc - free;
			}
			else {
				System.arraycopy(buf, off1, ucBuffer, ucBufferPtr, tc);
				ucBufferPtr += tc;
			}
			off1 += tc;
		}
	}
	
	/**
	 * Process some more data. When the internal buffer is full, or when the compressor code deems it appropriate, the
	 * data is compressed and sent on the transport stream. This method is most efficient when data is input by big
	 * enough chunks (at least a dozen bytes per call).
	 * 
	 * @param buf
	 *            the data buffer
	 * @param off
	 *            the data offset
	 * @param len
	 *            the data length (in bytes)
	 * @throws IOException
	 *             on I/O error with the transport stream
	 */
	public void process(byte[] buf, int off, int len) throws IOException {
		if (len == 0)
			return;
		int origOff = off;
		
		/*
		 * If in "Huffman only" mode, then we use an alternate loop.
		 */
		if (huffOnly) {
			int[] sb = buffer;
			int sbPtr = bufferPtr;
			int sbLen = sb.length;
			while (len-- > 0) {
				int bv = buf[off++] & 0xFF;
				sb[sbPtr++] = bv;
				if (sbPtr == sbLen) {
					updateUCBuffer(buf, origOff, off);
					origOff = off;
					endBlock(false, sbPtr);
					sbPtr = 0;
				}
			}
			bufferPtr = sbPtr;
			updateUCBuffer(buf, origOff, off);
			return;
		}
		
		/*
		 * We have some special code for the first two bytes ever.
		 */
		if (windowState < 2) {
			int bv = buf[off++] & 0xFF;
			len--;
			if (windowState == 0) {
				recentBytes = bv;
				seqLen = 1;
				if (len == 0) {
					updateUCBuffer(buf, origOff, off);
					return;
				}
				bv = buf[off++] & 0xFF;
				len--;
			}
			recentBytes = (recentBytes << 8) | bv;
			windowState = 2;
			seqLen = 2;
			if (len == 0) {
				updateUCBuffer(buf, origOff, off);
				return;
			}
		}
		
		/*
		 * We cache most instance fields in local variables. This helps the JIT compiler produce efficient code.
		 */
		int[] win = window;
		int winMask = win.length - 1;
		int winPtr = windowPtr;
		int recent = recentBytes;
		char[] wlink = windowLink;
		char[] ht = hashTable;
		int htMask = ht.length - 1;
		int sLen = seqLen;
		int sPtr = seqPtr;
		int sDist = seqDist;
		int[] sb = buffer;
		int sbPtr = bufferPtr;
		int sbLen = sb.length;
		int maxCL0 = maxChainLengthTriplet;
		int maxDistCL0 = maxDistanceTriplet;
		int maxCL1 = maxChainLengthSeq1;
		int maxDistCL1 = maxDistanceSeq1;
		int goodSLen = goodSequenceLength;
		int maxLLen = maxLazyLength;
		int maxCL2 = maxChainLengthSeq2;
		int maxDistCL2 = maxDistanceSeq2;
		
		loop: while (len-- > 0) {
			int b0 = buf[off++] & 0xFF;
			
			/*
			 * Compute the current triplet.
			 */
			int triplet = (recent << 8) | b0;
			recent = triplet & 0xFFFF;
			
			/*
			 * Update the window and set hash links.
			 */
			win[winPtr] = triplet;
			int h = (triplet + (triplet >>> 4) + (triplet >>> 8) + (triplet >>> 9) - (triplet >>> 16)) & htMask;
			int link = ht[h] - 1;
			int dist;
			if (link < 0) {
				dist = 0;
			}
			else {
				int pv = win[link];
				int ph = (pv + (pv >>> 4) + (pv >>> 8) + (pv >>> 9) - (pv >>> 16)) & htMask;
				if (ph == h) {
					dist = (winPtr - link) & winMask;
				}
				else {
					dist = 0;
				}
			}
			ht[h] = (char) (winPtr + 1);
			wlink[winPtr] = (char) dist;
			
			int thisPtr = winPtr;
			winPtr = (winPtr + 1) & winMask;
			
			/*
			 * If the current sequence length is 0 or 1, then we do not have a complete triplet yet, and there is
			 * nothing more to do.
			 */
			if (sLen < 2) {
				sLen++;
				continue loop;
			}
			
			/*
			 * If we just completed a triplet, then we look for a previous triplet. If we find one, then we begin a
			 * match sequence; otherwise, we emit a literal for the first byte of our triplet, and we continue. There is
			 * a corner case when the previous triplet is at the maximum distance: in that situation, we must emit the
			 * copy symbol immediately.
			 */
			if (sLen == 2) {
				if (dist == 0) {
					sb[sbPtr++] = triplet >>> 16;
					if (sbPtr == sbLen) {
						updateUCBuffer(buf, origOff, off);
						origOff = off;
						endBlock(false, sbPtr);
						sbPtr = 0;
					}
					continue loop;
				}
				sDist = dist;
				findTriplet: do {
					int n = link;
					int chainLen = maxCL0;
					while (chainLen-- > 0) {
						if (win[n] == triplet)
							break findTriplet;
						int d = wlink[n];
						if (d == 0)
							break;
						sDist += d;
						if (sDist > maxDistCL0)
							break;
						n = (n - d) & winMask;
					}
					sDist = 0;
				}
				while (false);
				if (sDist == 0) {
					sb[sbPtr++] = triplet >>> 16;
					if (sbPtr == sbLen) {
						updateUCBuffer(buf, origOff, off);
						origOff = off;
						endBlock(false, sbPtr);
						sbPtr = 0;
					}
				}
				else {
					sPtr = (thisPtr - sDist) & winMask;
					sLen = 3;
					if (sPtr == winPtr) {
						sb[sbPtr++] = makeCopySymbol(sLen, sDist);
						if (sbPtr == sbLen) {
							updateUCBuffer(buf, origOff, off);
							origOff = off;
							endBlock(false, sbPtr);
							sbPtr = 0;
						}
						sLen = 0;
					}
				}
				continue loop;
			}
			
			/*
			 * We are currently matching a sequence. We try to augment it. If we can, then we just do that; but we must
			 * mind the maximum sequence length and also its distance.
			 */
			if (win[(thisPtr - sDist) & winMask] == triplet) {
				sLen++;
				if (sPtr == winPtr || sLen == 258) {
					sb[sbPtr++] = makeCopySymbol(sLen, sDist);
					if (sbPtr == sbLen) {
						updateUCBuffer(buf, origOff, off);
						origOff = off;
						endBlock(false, sbPtr);
						sbPtr = 0;
					}
					sLen = 0;
				}
				continue loop;
			}
			
			/*
			 * The current sequence cannot be agumented. We try to find a longer match in the previous sequences.
			 */
			int sDistNew = findPreviousSequence(win, winMask, wlink, sPtr, sDist, sLen - 2, triplet, sLen > goodSLen ? (maxCL1 >>> 2) : maxCL1, maxDistCL1);
			if (sDistNew > 0) {
				sDist = sDistNew;
				sPtr = (thisPtr - (sLen - 2) - sDistNew) & winMask;
				sLen++;
				if (sPtr == winPtr || sLen == 258) {
					sb[sbPtr++] = makeCopySymbol(sLen, sDist);
					if (sbPtr == sbLen) {
						updateUCBuffer(buf, origOff, off);
						origOff = off;
						endBlock(false, sbPtr);
						sbPtr = 0;
					}
					sLen = 0;
				}
				continue loop;
			}
			
			/*
			 * We could not find a longer sequence. We must flush something. We try to find a longer sequence beginning
			 * at the next byte (that's what RFC 1951 calls lazy matching).
			 */
			if (sLen <= maxLLen) {
				int refPtr = (thisPtr - (sLen - 2) + 1) & winMask;
				int mdc = maxDistCL2;
				if (mdc > sDist)
					mdc = sDist;
				sDistNew = findPreviousSequence(win, winMask, wlink, refPtr, 0, sLen - 3, triplet, maxCL2, mdc);
				if (sDistNew > 0) {
					sb[sbPtr++] = win[sPtr] >>> 16;
					if (sbPtr == sbLen) {
						updateUCBuffer(buf, origOff, off);
						origOff = off;
						endBlock(false, sbPtr);
						sbPtr = 0;
					}
					sDist = sDistNew;
					sPtr = (refPtr - sDistNew) & winMask;
					if (sPtr == winPtr) {
						sb[sbPtr++] = makeCopySymbol(sLen, sDist);
						if (sbPtr == sbLen) {
							updateUCBuffer(buf, origOff, off);
							origOff = off;
							endBlock(false, sbPtr);
							sbPtr = 0;
						}
						sLen = 0;
					}
					continue loop;
				}
			}
			
			/*
			 * The current sequence is finished and we could not find any better. We filter out three-byte sequences
			 * which are too far: for these sequences, the copy symbol with its distance code and extra bits may be more
			 * expensive than simply writing out the literal bytes.
			 * 
			 * The threshold for this test has been determined by compressing many files and seeing how the result
			 * compares with what gzip produces. It seems that the "right" value is 6144.
			 */
			if (sLen == 3 && sDist > 6144) {
				int ot = win[sPtr];
				for (int k = 16; k >= 0; k -= 8) {
					sb[sbPtr++] = (ot >>> k) & 0xFF;
					if (sbPtr == sbLen) {
						updateUCBuffer(buf, origOff, off);
						origOff = off;
						endBlock(false, sbPtr);
						sbPtr = 0;
					}
				}
			}
			else {
				sb[sbPtr++] = makeCopySymbol(sLen, sDist);
				if (sbPtr == sbLen) {
					updateUCBuffer(buf, origOff, off);
					origOff = off;
					endBlock(false, sbPtr);
					sbPtr = 0;
				}
			}
			sLen = 1;
		}
		
		/*
		 * Flush out the local copies of the instance fields.
		 */
		windowPtr = winPtr;
		recentBytes = recent;
		seqLen = sLen;
		seqPtr = sPtr;
		seqDist = sDist;
		bufferPtr = sbPtr;
		
		/*
		 * Do not forget the original data bytes.
		 */
		updateUCBuffer(buf, origOff, off);
	}
	
	/**
	 * Prepare for a flush / terminate: the current dangling sequence is output.
	 * 
	 * @throws IOException
	 *             on I/O error with the transport stream
	 */
	private void prepareFlush() throws IOException {
		switch (seqLen) {
		case 0:
			break;
		case 1:
			buffer[bufferPtr++] = recentBytes & 0xFF;
			if (bufferPtr == buffer.length)
				endBlock(false, bufferPtr);
			break;
		case 2:
			buffer[bufferPtr++] = (recentBytes >>> 8) & 0xFF;
			if (bufferPtr == buffer.length)
				endBlock(false, bufferPtr);
			buffer[bufferPtr++] = recentBytes & 0xFF;
			if (bufferPtr == buffer.length)
				endBlock(false, bufferPtr);
			break;
		default:
			buffer[bufferPtr++] = makeCopySymbol(seqLen, seqDist);
			if (bufferPtr == buffer.length)
				endBlock(false, bufferPtr);
			break;
		}
		seqLen = 0;
	}
	
	/* =========================================================== */

	/*
	 * Huffman trees.
	 * 
	 * The optimal Huffman tree cannot always be used, because we have additional constraints on the code lengths. The
	 * generic algorithm is called "package-merge" but it is relatively expensive (O(Ln) where L is the maximum code
	 * length and n is the alphabet size. We rather implement a "tree tweak": we begin with the optimal Huffman tree,
	 * and then we tweak it until it fits in the length constraints (it turns out that zlib uses a very similar trick).
	 * 
	 * The tricky point is that once we have sorted the symbol by frequencies, then we just need to compute the number
	 * of codes for each code length. The actual code lengths for all symbols are easy to compute back by giving the
	 * longer codes to the least often encountered symbols (we may somehow obtain a locally optimal yet different tree,
	 * but this is no problem since in fine we will convert the tree to the canonical Huffman codes anyway). That idea
	 * apparently comes from someone called Haruhiko Okumura.
	 * 
	 * Tree tweaking is applied when the optimal tree has "overdeep" leaves. Basically, we have a number of non-leaf
	 * nodes at the maximum depth (call "p" that number of nodes). Each is the root for a subtree containing q_i leaves
	 * which are all overdeep (1 <= i <= q). If we have q = \sum_i q_i overdeep leaves, then we must relocate those q
	 * leaves: -- p leaves will use the locations held by the subtree roots at maximum depth; -- q-p leaves will have to
	 * be relocated elsewhere. Hence, a first pass is used to obtain the number of codes for all valid code lengths; in
	 * that pass, the subtree roots at maximum depth are accounted as leaves (this handles the p "natural" relocations),
	 * and the value of q-p is returned. Relocation for those values works thus: to relocate a leaf, find a valid code
	 * of length l (strictly lower than the maximum length) and replace it with two codes of length l+1: the code is
	 * lengthened by one bit, which leaves room for one brother. We iterate over all leaves to relocate, choosing each
	 * time the longest possible code.
	 * 
	 * It shall be noted that for the values used in DEFLATE, it is not possible that _all_ optimal codes exceed the
	 * maximum code length. Actually, the tweaking mechanism cannot fail with the parameters which will be used.
	 * 
	 * The sorting step uses the Heap Sort, because it is an elegant algorithm which uses O(1) additional space and has
	 * a nice O(n log n) guaranteed worst case. A QuickSort would be faster on average, but I like the Heap Sort better,
	 * and this part is not a CPU bottleneck anyway.
	 */

	/**
	 * Sort the provided array of integer values (ascending order). Only the values between indexes <code>off</code>
	 * (inclusive) and <code>off + len</code> (exclusive) are touched; other array elements are neither considered nor
	 * modified.
	 * 
	 * @param val
	 *            the array to sort
	 * @param off
	 *            the array offset
	 * @param len
	 *            the number of elements to sort
	 */
	private static void heapSort(int[] val, int off, int len) {
		if (len <= 1)
			return;
		
		/*
		 * We use virtual indexes, between 1 and len (inclusive). In our internal heap, the two children of node of
		 * virtual index "i" have virtual indexes "2*i" and "2*i+1".
		 */
		int corr = off - 1;
		
		/*
		 * Build the heap by inserting all values at the bottom, and then making them float up as necessary.
		 */
		for (int i = 2; i <= len; i++) {
			int j = i;
			int v = val[corr + j];
			while (j > 1) {
				int k = j >>> 1;
				int f = val[corr + k];
				if (f > v)
					break;
				val[corr + j] = f;
				val[corr + k] = v;
				j = k;
			}
		}
		
		/*
		 * Repeatedly, extract the heap root (maximum element) and rebuild the heap by promoting the bottom element and
		 * having it sink to its proper place.
		 */
		for (int i = len; i > 1; i--) {
			int v = val[corr + i];
			val[corr + i] = val[corr + 1];
			val[corr + 1] = v;
			int j = 1;
			for (;;) {
				int kl = j << 1;
				int kr = kl + 1;
				if (kl >= i)
					break;
				int si;
				int sv;
				if (kr >= i) {
					si = kl;
					sv = val[corr + kl];
				}
				else {
					int cl = val[corr + kl];
					int cr = val[corr + kr];
					if (cl > cr) {
						si = kl;
						sv = cl;
					}
					else {
						si = kr;
						sv = cr;
					}
				}
				if (v > sv)
					break;
				val[corr + j] = sv;
				val[corr + si] = v;
				j = si;
			}
		}
	}
	
	/**
	 * <p>
	 * Compute the optimal Huffman tree, then tweak it so that it fits within the specified maximum code length.
	 * Returned value is the resulting code length for each symbol. From these lengths, the canonical Huffman code can
	 * be rebuilt.
	 * </p>
	 * 
	 * <p>
	 * The alphabet size is assumed to be equal to <code>freq.length</code>.
	 * </p>
	 * 
	 * <p>
	 * The following properties MUST be met:
	 * <ul>
	 * <li>frequencies are positive integers (0 is allowed, and qualifies a symbol which does not occur at all);</li>
	 * <li>the sum of all frequencies must be strictly lower than 2097152 (i.e. that sum must fit in a 21-bit unsigned
	 * integer field).</li>
	 * </ul>
	 * </p>
	 * 
	 * @param freq
	 *            the symbol frequencies
	 * @param maxCodeLen
	 *            the maximum code length
	 * @return the resulting code lengths
	 */
	private static int[] makeHuffmanCodes(int[] freq, int maxCodeLen) {
		int alphLen = freq.length;
		
		/*
		 * We sort symbols by frequencies. Each value in freqTmp[] consists of: -- symbol value (9 bits) -- flag
		 * "literal" set (1 bit, equal to 1) -- symbol frequency (21 bits)
		 * 
		 * Since the frequencies use the upper bits, we can compare those values directly. A side effect is that no two
		 * values will be considered as equal to each other, even for two symbols which occur with the same frequency;
		 * this is harmless.
		 */
		int[] freqTmp = new int[alphLen];
		for (int i = 0; i < alphLen; i++)
			freqTmp[i] = i + (1 << 9) + (freq[i] << 10);
		heapSort(freqTmp, 0, alphLen);
		
		/*
		 * We skip the values with frequency zero; they will not take part in the tree construction. We handle
		 * immediately the special case where only one symbol occurs.
		 */
		int freqTmpPtr = 0;
		while (freqTmpPtr < alphLen && (freqTmp[freqTmpPtr] >>> 10) == 0)
			freqTmpPtr++;
		if (freqTmpPtr == alphLen) {
			/*
			 * No symbol occurs (degenerate case).
			 */
			return new int[alphLen];
		}
		if (freqTmpPtr == (alphLen - 1)) {
			/*
			 * Only onw symbol occurs.
			 */
			int[] clen = new int[alphLen];
			clen[freqTmp[freqTmpPtr] & 0x1FF] = 1;
			return clen;
		}
		
		/*
		 * We use an additional FIFO in which we store the built tree nodes. All nodes make it to that fifo, and we do
		 * not move elements inside it; rather, we shift the limit indexes. There are at most alphLen-1 nodes in the
		 * tree (possibly less if some symbols are not used).
		 */
		int[] fifo = new int[alphLen - 1];
		int fifoHead = 0, fifoRear = 0;
		
		/*
		 * The tree is stored in an array. Each array element specifies a non-leaf node and consists of two 10-bit
		 * values, for the left and right node children, respectively. Each such 10-bit value specifies either a leaf
		 * symbol (9-bit value, 10th bit set) or the index for a non-leaf child node (9-bit index, 10th bit cleared).
		 * The tree root index is stored in rootIndex when the tree is finished.
		 * 
		 * The tree is complete (no missing child anywhere) and has at most alphLen leaves (since we skip symbols which
		 * do not occur, the actual tree can be smaller); hence, it may have up to alphLen-1 non-leaf nodes.
		 */
		int[] tree = new int[alphLen - 1];
		int treePtr = 0;
		int rootIndex;
		
		/*
		 * Tree building uses the classical Huffman code construction algorithm. We have two queues, the one in
		 * freqTmp[] (the yet unprocessed leaves) and the one in fifo[] (the built subtrees). At each step, we take the
		 * two least frequent elements (not necessarily one from each queue) and assemble them into a new subtree, which
		 * we insert in the fifo. The algorithm ends when freqTmp[] is empty (all symbols attached) and fifo[] contains
		 * a single subtree (which is the complete tree).
		 */
		for (;;) {
			if (freqTmpPtr == alphLen && fifoRear == (fifoHead + 1)) {
				rootIndex = fifo[fifoHead] & 0x1FF;
				break;
			}
			int n0, n1;
			if (fifoRear == fifoHead) {
				n0 = freqTmp[freqTmpPtr++];
				n1 = freqTmp[freqTmpPtr++];
			}
			else if (freqTmpPtr == alphLen) {
				n0 = fifo[fifoHead++];
				n1 = fifo[fifoHead++];
			}
			else {
				int f = fifo[fifoHead];
				int q = freqTmp[freqTmpPtr];
				if (f < q) {
					n0 = f;
					fifoHead++;
				}
				else {
					n0 = q;
					freqTmpPtr++;
				}
				if (fifoHead == fifoRear) {
					n1 = freqTmp[freqTmpPtr++];
				}
				else if (freqTmpPtr == alphLen) {
					n1 = fifo[fifoHead++];
				}
				else {
					f = fifo[fifoHead];
					q = freqTmp[freqTmpPtr];
					if (f < q) {
						n1 = f;
						fifoHead++;
					}
					else {
						n1 = q;
						freqTmpPtr++;
					}
				}
			}
			int ni = (n0 & ~0x3FF) + (n1 & ~0x3FF) + treePtr;
			fifo[fifoRear++] = ni;
			int nv = (n0 & 0x3FF) + ((n1 & 0x3FF) << 10);
			tree[treePtr++] = nv;
		}
		
		/*
		 * Now, we gather the count for each code length, and the number of overdeep leaves which must be relocated.
		 */
		int[] blCount = new int[maxCodeLen + 1];
		int overdeep = getCodeLengths(tree, rootIndex, 0, blCount, maxCodeLen);
		
		/*
		 * We relocate the overdeep leaves.
		 * 
		 * "dpi" contains the code length where we find nodes to demote (i.e. for which we lengthen the code) in order
		 * to find some room for a relocated leaf. That length is kept as long as possible, provided that it is strictly
		 * lower than maxCodeLen, and that there remain codes with that length.
		 */
		int dpi = maxCodeLen;
		while (overdeep-- > 0) {
			if (dpi == maxCodeLen) {
				do {
					dpi--;
				}
				while (blCount[dpi] == 0);
			}
			blCount[dpi]--;
			blCount[++dpi] += 2;
		}
		
		/*
		 * We rebuild the code lengths. We just walk the symbols sorted by frequencies, and give them code lengths,
		 * according to the counts in blCount[].
		 */
		int[] codeLen = new int[alphLen];
		int p = 0;
		while ((freqTmp[p] >>> 10) == 0)
			p++;
		for (int bits = maxCodeLen; bits > 0; bits--) {
			for (int k = blCount[bits]; k > 0; k--) {
				int sym = freqTmp[p++] & 0x1FF;
				codeLen[sym] = bits;
			}
		}
		
		/*
		 * We have finished: we computed the code lengths for all symbols. These code lengths are for an optimized (not
		 * necessarily optimal) Huffman tree which fits in the allowed maximum code length.
		 */
		return codeLen;
	}
	
	/**
	 * Count the code lengths for the provided subtree; the counts are accumulated in the <code>blCount[]</code> array.
	 * Non-leaf nodes at exactly the maximum depth are accounted as leaves with the maximum code length. The number of
	 * overdeep leaves which must be relocated is returned.
	 * 
	 * @param tree
	 *            the tree array
	 * @param idx
	 *            the subtree root index (or leaf code)
	 * @param depth
	 *            the subtree root depth
	 * @param blCount
	 *            the code length count accumulator array
	 * @param maxCodeLen
	 *            the maximum code length
	 * @return the number of overdeep leaves to relocate
	 */
	private static int getCodeLengths(int[] tree, int idx, int depth, int[] blCount, int maxCodeLen) {
		if ((idx & 0x200) != 0) {
			if (depth > maxCodeLen) {
				return 1;
			}
			else {
				blCount[depth]++;
				return 0;
			}
		}
		
		int s;
		if (depth == maxCodeLen) {
			blCount[maxCodeLen]++;
			s = -1;
		}
		else {
			s = 0;
		}
		int n = tree[idx];
		int l = n & 0x3FF;
		int r = (n >>> 10) & 0x3FF;
		s += getCodeLengths(tree, l, depth + 1, blCount, maxCodeLen);
		s += getCodeLengths(tree, r, depth + 1, blCount, maxCodeLen);
		return s;
	}
	
	/**
	 * The fixed Huffman codes, for the literal+length alphabet.
	 */
	private static final int[] FIXED_LIT_CODE;
	
	static {
		int[] fixedLitCodeLen = new int[288];
		for (int i = 0; i < 144; i++)
			fixedLitCodeLen[i] = 8;
		for (int i = 144; i < 256; i++)
			fixedLitCodeLen[i] = 9;
		for (int i = 256; i < 280; i++)
			fixedLitCodeLen[i] = 7;
		for (int i = 280; i < 288; i++)
			fixedLitCodeLen[i] = 8;
		FIXED_LIT_CODE = makeCanonicalHuff(fixedLitCodeLen, 15);
	}
	
	/**
	 * The fixed Huffman codes, for the distance alphabet.
	 */
	private static final int[] FIXED_DIST_CODE;
	
	static {
		int[] fixedDistCodeLen = new int[32];
		for (int i = 0; i < 32; i++)
			fixedDistCodeLen[i] = 5;
		FIXED_DIST_CODE = makeCanonicalHuff(fixedDistCodeLen, 15);
	}
	
	/**
	 * RLE-compress two Huffman trees (for the literal+length and distance alphabets, represented as arrays of code
	 * lengths). This results in values from 0 to 18 (5 low bits), where values 16, 17 and 18 have extra bits (bit 5 and
	 * beyond). The frequencies for the resulting values are also accumulated in the freq[] array.
	 * 
	 * @param tree1
	 *            the first tree
	 * @param tree1len
	 *            the first tree actual length
	 * @param tree2
	 *            the second tree
	 * @param tree2len
	 *            the second tree actual length
	 * @param freq
	 *            an array which receives frequencies
	 * @return the compressed trees
	 */
	private static int[] compressTrees(int[] tree1, int tree1len, int[] tree2, int tree2len, int[] freq) {
		int inLen = tree1len + tree2len;
		int[] in = new int[inLen];
		System.arraycopy(tree1, 0, in, 0, tree1len);
		System.arraycopy(tree2, 0, in, tree1len, tree2len);
		
		int ptr = 0;
		int[] ct = new int[inLen];
		int ctPtr = 0;
		while (ptr < inLen) {
			int v = in[ptr++];
			if (v == 0) {
				int r = 1;
				while (r < 138 && ptr < inLen) {
					if (in[ptr] != 0)
						break;
					r++;
					ptr++;
				}
				switch (r) {
				case 1:
					ct[ctPtr++] = 0;
					freq[0]++;
					break;
				case 2:
					ct[ctPtr++] = 0;
					ct[ctPtr++] = 0;
					freq[0] += 2;
					break;
				default:
					if (r <= 10) {
						ct[ctPtr++] = 17 + ((r - 3) << 5);
						freq[17]++;
					}
					else {
						ct[ctPtr++] = 18 + ((r - 11) << 5);
						freq[18]++;
					}
					break;
				}
			}
			else {
				int r = 0;
				while (r < 6 && ptr < inLen) {
					if (in[ptr] != v)
						break;
					r++;
					ptr++;
				}
				ct[ctPtr++] = v;
				freq[v]++;
				switch (r) {
				case 0:
					break;
				case 1:
					ct[ctPtr++] = v;
					freq[v]++;
					break;
				case 2:
					ct[ctPtr++] = v;
					ct[ctPtr++] = v;
					freq[v] += 2;
					break;
				default:
					ct[ctPtr++] = 16 + ((r - 3) << 5);
					freq[16]++;
					break;
				}
			}
		}
		
		int[] res = new int[ctPtr];
		System.arraycopy(ct, 0, res, 0, ctPtr);
		return res;
	}
	
	/**
	 * This array encodes the permutation for the values encoding the RLE-compressed trees.
	 */
	
	/* =========================================================== */
	/*
	 * Block assembly and data output.
	 * 
	 * The LZ77 code assembles symbols in the buffer[] array. We compute appropriate Huffman trees, and then produce the
	 * compressed blocks. We first get all frequencies, compute the dynamic Huffman trees, and deduce the compressed
	 * block size. We also use the frequencies to know what size we would get with the fixed Huffman trees. We compare
	 * these two sizes with each other, and with the size for uncompressed blocks; we will use whichever method yields
	 * the smallest size.
	 * 
	 * When producing an uncompressed block, we need to rebuild the uncompressed data. The ucBuffer[] array contains the
	 * first uncompressed bytes for this block; the subsequent bytes can be deduced by using the buffered symbols.
	 * 
	 * Technically, we could use the same technique for type 2 blocks, in order to know whether a given short sequence
	 * is worth copying. But this is tricky because if we decide to switch symbols at that time, then the Huffman codes
	 * themselves have been computed with "wrong" frequencies. Right now, we filter out those sequences heuristically in
	 * the LZ77 stage.
	 */

	/**
	 * Write out some bits (least significant bit exits first).
	 * 
	 * @param val
	 *            the bit values
	 * @param num
	 *            the number of bits (possibly 0)
	 * @throws IOException
	 *             on I/O error with the transport stream
	 */
	private void writeBits(int val, int num) throws IOException {
		if (num == 0)
			return;
		for (;;) {
			int fs = 8 - outPtr;
			int v = outByte | (val << outPtr);
			if (fs > num) {
				outByte = v;
				outPtr += num;
				return;
			}
			else if (fs == num) {
				outBuf[outBufPtr++] = (byte) v;
				if (outBufPtr == outBuf.length) {
					out.write(outBuf);
					outBufPtr = 0;
				}
				outByte = 0;
				outPtr = 0;
				return;
			}
			outBuf[outBufPtr++] = (byte) v;
			if (outBufPtr == outBuf.length) {
				out.write(outBuf);
				outBufPtr = 0;
			}
			val >>>= fs;
			num -= fs;
			outByte = 0;
			outPtr = 0;
		}
	}
	
	/**
	 * Send buffered complete output bytes.
	 * 
	 * @throws IOException
	 *             on I/O error with the transport stream
	 */
	private void sendBuffered() throws IOException {
		if (outBufPtr > 0) {
			out.write(outBuf, 0, outBufPtr);
			outBufPtr = 0;
		}
	}
	
	/**
	 * End the current block and compress it. The <code>bufferPtr</code> field value may be incorrect; the value
	 * provided in the <code>sbPtr</code> parameter must be used instead. This method resets the <code>bufferPtr</code>
	 * field to 0.
	 * 
	 * @param fin
	 *            <code>true</code> for the final block
	 * @param sbPtr
	 *            the correct value for <code>bufferPtr</code>
	 * @throws IOException
	 *             on I/O error with the transport stream
	 */
	private void endBlock(boolean fin, int sbPtr) throws IOException {
		if (noOutput) {
			bufferPtr = 0;
			return;
		}
		
		int[] sb = buffer;
		
		int[] freqLit = new int[286];
		int[] freqDist = new int[30];
		
		/*
		 * Do not forget the EOB symbol.
		 */
		freqLit[256] = 1;
		
		/*
		 * Get the uncompressed data length; also, gather frequencies.
		 */
		int csU = 0;
		for (int i = 0; i < sbPtr; i++) {
			int val = sb[i];
			int sym = val & 0x1FF;
			freqLit[sym]++;
			if (sym < 256) {
				csU += 8;
				continue;
			}
			csU += 8 * (LENGTH[sym - 257] + ((val >>> 9) & 0x1F));
			int dist = (val >>> 14) & 0x1F;
			freqDist[dist]++;
		}
		
		/*
		 * Adjust csU to account for the header bytes. Also, save the uncompressed data length (in bytes) in uDataLen.
		 */
		int csUextra = 0;
		for (int t = 0; t < csU; t += 65535) {
			if (t == 0) {
				if (outPtr > 5) {
					csUextra = 48 - outPtr;
				}
				else {
					csUextra = 40 - outPtr;
				}
			}
			else {
				csUextra += 40;
			}
		}
		int uDataLen = (csU >>> 3);
		csU += csUextra;
		
		/*
		 * Compute the dynamic Huffman codes and get the lengths for dynamic and fixed codes.
		 */
		Huff huff = new Huff(freqLit, freqDist);
		int csD = huff.getDynamicBitLength();
		int csF = huff.getFixedBitLength();
		
		/*
		 * We now have the bit lengths for uncompressed blocks (csU), fixed Huffman codes (csF) and dynamic Huffman
		 * codes (csD). We use the smallest. On equality, we prefer uncompressed blocks over Huffman codes, and fixed
		 * Huffman codes over dynamic Huffman codes.
		 */
		if (csU <= csF && csU <= csD) {
			writeBits(fin ? 1 : 0, 3);
			if (outPtr > 0)
				writeBits(0, 8 - outPtr);
			writeBits(uDataLen | (~uDataLen << 16), 32);
			sendBuffered();
			out.write(ucBuffer, 0, uDataLen);
		}
		else if (csF <= csD) {
			/*
			 * Fixed Huffman codes.
			 */

			/*
			 * Block header (3 bits).
			 */
			writeBits(fin ? 3 : 2, 3);
			
			/*
			 * Now, write out the data.
			 */
			for (int i = 0; i < sbPtr; i++) {
				int val = buffer[i];
				int sym = val & 0x1FF;
				if (sym < 256) {
					writeBits(FIXED_LIT_CODE[sym], sym < 144 ? 8 : 9);
					continue;
				}
				
				writeBits(FIXED_LIT_CODE[sym], sym < 280 ? 7 : 8);
				int eLenNum = LENGTH_ENUM[sym - 257];
				if (eLenNum > 0)
					writeBits((val >>> 9) & 0x1F, eLenNum);
				int dist = (val >>> 14) & 0x1F;
				writeBits(FIXED_DIST_CODE[dist], 5);
				int eDistNum = DIST_ENUM[dist];
				if (eDistNum > 0)
					writeBits(val >>> 19, eDistNum);
			}
			
			/*
			 * Write out the EOB.
			 */
			writeBits(FIXED_LIT_CODE[256], 7);
		}
		else {
			/*
			 * Dynamic Huffman codes.
			 */
			int[] litCode = huff.getLitCode();
			int[] litCodeLen = huff.getLitCodeLen();
			int[] distCode = huff.getDistCode();
			int[] distCodeLen = huff.getDistCodeLen();
			int[] compTrees = huff.getCompTrees();
			int compTreesLen = compTrees.length;
			int[] ctCode = huff.getCTCode();
			int[] ctCodeLen = huff.getCTCodeLen();
			int[] permCT = huff.getPermCT();
			int permCTLen = permCT.length;
			
			/*
			 * Block header (3 bits).
			 */
			writeBits(fin ? 5 : 4, 3);
			
			/*
			 * The tree lengths.
			 */
			writeBits(litCode.length - 257, 5);
			writeBits(distCode.length - 1, 5);
			writeBits(permCTLen - 4, 4);
			
			/*
			 * The CT tree.
			 */
			for (int i = 0; i < permCTLen; i++)
				writeBits(permCT[i], 3);
			
			/*
			 * The two compressed trees.
			 */
			for (int i = 0; i < compTreesLen; i++) {
				int v = compTrees[i];
				int s = v & 0x1F;
				writeBits(ctCode[s], ctCodeLen[s]);
				int ebits;
				switch (s) {
				case 16:
					ebits = 2;
					break;
				case 17:
					ebits = 3;
					break;
				case 18:
					ebits = 7;
					break;
				default:
					continue;
				}
				writeBits((v >>> 5), ebits);
			}
			
			/*
			 * Now, write out the data.
			 */
			for (int i = 0; i < sbPtr; i++) {
				int val = buffer[i];
				int sym = val & 0x1FF;
				writeBits(litCode[sym], litCodeLen[sym]);
				if (sym < 256)
					continue;
				int eLenNum = LENGTH_ENUM[sym - 257];
				if (eLenNum > 0)
					writeBits((val >>> 9) & 0x1F, eLenNum);
				int dist = (val >>> 14) & 0x1F;
				writeBits(distCode[dist], distCodeLen[dist]);
				int eDistNum = DIST_ENUM[dist];
				if (eDistNum > 0)
					writeBits(val >>> 19, eDistNum);
			}
			
			/*
			 * Write out the EOB.
			 */
			writeBits(litCode[256], litCodeLen[256]);
		}
		
		sendBuffered();
		bufferPtr = 0;
		
		/*
		 * Adjust ucBuffer[]. Some data has been processed, but some may remain (at most 258 bytes, corresponding to the
		 * currently matched sequence). We must take care: the buffer is circular. We use the fact that the buffer is
		 * more than twice as large than the maximum amout of data we move around.
		 */
		int uLen = ucBuffer.length;
		int uRealLen = ucBufferPtr;
		while (uRealLen < uDataLen)
			uRealLen += uLen;
		if (uDataLen < uRealLen) {
			int tm = uRealLen - uDataLen;
			
			/* DEBUG */
			if (tm > 258)
				throw new Error("too much data: " + tm);
			
			if (tm <= ucBufferPtr) {
				System.arraycopy(ucBuffer, ucBufferPtr - tm, ucBuffer, 0, tm);
			}
			else {
				int fpl = tm - ucBufferPtr;
				System.arraycopy(ucBuffer, 0, ucBuffer, fpl, ucBufferPtr);
				System.arraycopy(ucBuffer, uLen - fpl, ucBuffer, 0, fpl);
			}
		}
		ucBufferPtr = uRealLen - uDataLen;
	}
	
	/**
	 * Instances of this class compute the dynamic Huffman codes for some frequencies, and report the resulting length,
	 * for both dynamic and static codes.
	 */
	private static class Huff {
		
		private int[] litCode, litCodeLen;
		private int[] distCode, distCodeLen;
		private int[] compTrees;
		private int[] ctCode, ctCodeLen;
		private int[] permCT;
		private int csD, csF;
		
		/**
		 * Build the instance with the provided frequencies for the literal+length and the distance alphabets. The first
		 * frequency array MUST include the value 1 for the EOB symbol (value 256).
		 * 
		 * @param freqLit
		 *            the literal+length frequencies
		 * @param freqDist
		 *            the distance frequencies
		 */
		private Huff(int[] freqLit, int[] freqDist) {
			csD = 17;
			csF = 3;
			
			litCodeLen = makeHuffmanCodes(freqLit, 15);
			distCodeLen = makeHuffmanCodes(freqDist, 15);
			
			for (int i = 0; i < litCodeLen.length; i++) {
				int f = freqLit[i];
				int elen;
				elen = (i >= 257) ? LENGTH_ENUM[i - 257] : 0;
				csD += (litCodeLen[i] + elen) * f;
				int fcl;
				if (i < 256) {
					fcl = (i < 144) ? 8 : 9;
				}
				else {
					fcl = (i < 280) ? 7 : 8;
				}
				csF += (fcl + elen) * f;
			}
			for (int i = 0; i < distCodeLen.length; i++) {
				int f = freqDist[i];
				int edist = DIST_ENUM[i];
				csD += (distCodeLen[i] + edist) * f;
				csF += (5 + edist) * f;
			}
			
			/*
			 * RLE-compress the two codes.
			 */
			litCode = makeCanonicalHuff(litCodeLen, 15);
			distCode = makeCanonicalHuff(distCodeLen, 15);
			if (distCode.length == 0)
				distCode = new int[1];
			int[] freqCT = new int[19];
			compTrees = compressTrees(litCodeLen, litCode.length, distCodeLen, distCode.length, freqCT);
			
			/*
			 * Compute the Huffman tree for the RLE-compressed trees.
			 */
			ctCodeLen = makeHuffmanCodes(freqCT, 7);
			ctCode = makeCanonicalHuff(ctCodeLen, 7);
			for (int i = 0; i < 19; i++) {
				int ccl = ctCodeLen[i];
				switch (i) {
				case 16:
					ccl += 2;
					break;
				case 17:
					ccl += 3;
					break;
				case 18:
					ccl += 7;
					break;
				}
				csD += freqCT[i] * ccl;
			}
			
			/*
			 * Compute the permuted tree for the RLE-compressed trees, and its minimal length.
			 */
			int[] permCTtmp = new int[19];
			int permCTLen = 0;
			for (int i = 0; i < 19; i++) {
				int len = ctCodeLen[PERM_CT[i]];
				if (len > 0)
					permCTLen = i + 1;
				permCTtmp[i] = len;
			}
			permCT = new int[permCTLen];
			System.arraycopy(permCTtmp, 0, permCT, 0, permCTLen);
			csD += 3 * permCTLen;
		}
		
		/**
		 * Get the literal code values.
		 * 
		 * @return the literal code values
		 */
		private int[] getLitCode() {
			return litCode;
		}
		
		/**
		 * Get the literal code lengths.
		 * 
		 * @return the literal code lengths
		 */
		private int[] getLitCodeLen() {
			return litCodeLen;
		}
		
		/**
		 * Get the distance code lengths.
		 * 
		 * @return the distance code lengths
		 */
		private int[] getDistCode() {
			return distCode;
		}
		
		/**
		 * Get the distance code lengths.
		 * 
		 * @return the distance code lengths
		 */
		private int[] getDistCodeLen() {
			return distCodeLen;
		}
		
		/**
		 * Get the RLE-compressed tree representation.
		 * 
		 * @return the RLE-compressed representation
		 */
		private int[] getCompTrees() {
			return compTrees;
		}
		
		/**
		 * Get the level-2 code values.
		 * 
		 * @return the level-2 code values
		 */
		private int[] getCTCode() {
			return ctCode;
		}
		
		/**
		 * Get the level-2 code lengths.
		 * 
		 * @return the level-2 code lengths
		 */
		private int[] getCTCodeLen() {
			return ctCodeLen;
		}
		
		/**
		 * Get the level-2 code lengths, permuted.
		 * 
		 * @return the permuted level-2 code lengths
		 */
		private int[] getPermCT() {
			return permCT;
		}
		
		/**
		 * Get the block length, in bits, if dynamic Huffman codes are used.
		 * 
		 * @return the block length with dynamic codes
		 */
		private int getDynamicBitLength() {
			return csD;
		}
		
		/**
		 * Get the block length, in bits, if fixed Huffman codes are used.
		 * 
		 * @return the block length with fixed codes
		 */
		private int getFixedBitLength() {
			return csF;
		}
	}
	
	/* =========================================================== */
	/*
	 * Flush handling.
	 * 
	 * We need some handling for the end of stream. If we have some buffered data, then we may just end the current
	 * block with the "final" flag set; otherwise, we need a new empty block to set that flag.
	 * 
	 * We also implement two flush modes. The "partial flush" mimics what zlib does with Z_PARTIAL_FLUSH. Recent
	 * versions of zlib deprecate that option, and do not document it any more, but it is needed for the implementation
	 * of some protocols, e.g. OpenSSH. With this flush mode, we add one or two empty blocks (with fixed Huffman trees),
	 * in order to make sure that the peer has enough compressed data to decompress all meaningful bytes. Whether we
	 * need to add one or two blocks depends on a computation, which hinges on the idea that zlib uses 9 bits of
	 * lookahead.
	 * 
	 * The "sync flush" terminates the current block (if any) and appends an empty "uncompressed data" block. That block
	 * includes automatic byte alignment, and ends with the four-byte sequence 00 00 FF FF. A common convention is _not
	 * to include_ that four-byte sequence, in which case the receiver is responsible for adding them. PPP uses this
	 * convention (see RFC 1979). We implement this mode, using a parameter flag to decide whether the four-byte
	 * sequence must be included or not.
	 */

	/**
	 * Write out an empty type 1 block (fixed Huffman trees).
	 * 
	 * @param fin
	 *            <code>true</code> for a final block
	 * @throws IOException
	 *             on I/O error with the transport stream
	 */
	private void writeEmptySH(boolean fin) throws IOException {
		writeBits(fin ? 3 : 2, 10);
	}
	
	/**
	 * Write out an empty type 0 block (uncompressed data).
	 * 
	 * @param fin
	 *            <code>true</code> for a final block
	 * @param wd
	 *            <code>false</code> to omit the 00 00 FF FF sequence
	 * @throws IOException
	 *             on I/O error with the transport stream
	 */
	private void writeEmptyUD(boolean fin, boolean wd) throws IOException {
		writeBits(fin ? 1 : 0, 3);
		if (outPtr > 0)
			writeBits(0, 8 - outPtr);
		if (wd)
			writeBits(0xFFFF0000, 32);
	}
	
	/**
	 * Terminate the current compression run. Pending buffered data, if any, is compressed as a final block, and written
	 * out on the transport stream. If there is no pending buffered data, then an empty, final block is added. Either
	 * way, any remaining partial byte is padded with zeroes and written. The transport stream is NOT flushed.
	 * 
	 * @throws IOException
	 *             on I/O error with the transport stream
	 */
	public void terminate() throws IOException {
		prepareFlush();
		if (bufferPtr == 0) {
			writeEmptySH(true);
		}
		else {
			endBlock(true, bufferPtr);
		}
		if (outPtr > 0)
			writeBits(0, 8 - outPtr);
		sendBuffered();
	}
	
	/**
	 * Perform a "sync flush" in a way similar to what is done by zlib with option <code>Z_SYNC_FLUSH</code>. The
	 * current block, if any, is closed, and one empty type 0 block is added. After this call, the stream is
	 * byte-aligned. The type 0 block ends with the aligned four-byte sequence 00 00 FF FF; these four bytes are omitted
	 * if <code>withData</code> is <code>false</code>. The transport stream is NOT flushed.
	 * 
	 * @param withData
	 *            <code>false</code> to omit the 00 00 FF FF bytes
	 * @throws IOException
	 *             on I/O error with the transport stream
	 */
	public void flushSync(boolean withData) throws IOException {
		prepareFlush();
		if (bufferPtr != 0)
			endBlock(false, bufferPtr);
		writeEmptyUD(false, withData);
		sendBuffered();
	}
	
	
	/**
	 * <code>LENGTH[n]</code> contains the sequence copy length
	 * when the symbol <code>257+n</code> has been read. The actual
	 * copy length may be augmented with a value from some extra bits.
	 */
	static final int[] LENGTH;

	/**
	 * <code>LENGTH_ENUM[n]</code> contains the number of extra bits
	 * which shall be read, in order to augment the sequence copy
	 * length corresponding to the symbol<code>257+n</code>.
	 */
	static final int[] LENGTH_ENUM = {
		0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
		3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0
	};

	/*
	 * Here, we initialize LENGTH[] from LENGTH_ENUM[].
	 */
	static {
		LENGTH = new int[29];
		LENGTH[0] = 3;
		int l = 3;
		for (int i = 1; i < 28; i ++) {
			l += 1 << LENGTH_ENUM[i - 1];
			LENGTH[i] = l;
		}
		/*
		 * The RFC 1951 specifies that the last symbol specifies
		 * a copy length of 258, not 259. I don't know why.
		 */
		LENGTH[28] = 258;
	}

	/**
	 * <code>DIST[n]</code> is the copy sequence distance corresponding
	 * to the distance symbol <code>n</code>, possibly augmented by
	 * some extra bits.
	 */
	static final int[] DIST;

	/**
	 * <code>DIST_ENUM[n]</code> contains the number of extra bits
	 * used to augment the copy sequence distance corresponding to
	 * the distance symbol <code>n</code>.
	 */
	static final int[] DIST_ENUM = {
		0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7,
		8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13
	};

	/*
	 * DIST[] is initialized from DIST_ENUM[].
	 */
	static {
		DIST = new int[30];
		DIST[0] = 1;
		int d = 1;
		for (int i = 1; i < 30; i ++) {
			d += 1 << DIST_ENUM[i - 1];
			DIST[i] = d;
		}
	}

	/**
	 * This array encodes the permutation for the values encoding
	 * the RLE-compressed trees.
	 */
	static final int[] PERM_CT = {
		16, 17, 18, 0, 8, 7, 9, 6, 10, 5,
		11, 4, 12, 3, 13, 2, 14, 1, 15
	};

	/**
	 * Build the canonical Huffman codes, given the length of each
	 * code. <code>null</code> is returned if the code is not
	 * correct. The returned array is trimmed to its minimal size
	 * (trailing codes which do not occur are removed). The codes
	 * are "reversed" (first bit is least significant).
	 *
	 * @param codeLen      the code lengths
	 * @param maxCodeLen   the maximum code length
	 * @return  the codes, or <code>null</code>
	 */
	static int[] makeCanonicalHuff(int[] codeLen, int maxCodeLen)
	{
		int alphLen = codeLen.length;
		int actualAlphLen = 0;

		/*
		 * Compute the number of codes for each length
		 * (by convention, there is no code of length 0).
		 */
		int[] blCount = new int[maxCodeLen + 1];
		for (int n = 0; n < alphLen; n ++) {
			int len = codeLen[n];
			if (len < 0 || len > maxCodeLen)
				return null;
			if (len > 0) {
				actualAlphLen = n + 1;
				blCount[len] ++;
			}
		}

		/*
		 * Compute the smallest code for each code length.
		 */
		int[] nextCode = new int[maxCodeLen + 1];
		int codeVal = 0;
		for (int bits = 1; bits <= maxCodeLen; bits ++) {
			codeVal = (codeVal + blCount[bits - 1]) << 1;
			nextCode[bits] = codeVal;
		}

		/*
		 * Compute the code itself for each synbol. We also
		 * count the number of distinct symbols which may appear.
		 */
		int[] code = new int[actualAlphLen];
		for (int n = 0; n < actualAlphLen; n ++) {
			int len = codeLen[n];
			if (len != 0) {
				int w = nextCode[len];
				if (w >= (1 << len))
					return null;
				code[n] = reverse(w, len);
				nextCode[len] = w + 1;
			}
		}

		return code;
	}

	/**
	 * Bit reverse a value.
	 *
	 * @param cc   the value to reverse
	 * @param q    the value length, in bits
	 * @return  the reversed value
	 */
	private static int reverse(int cc, int q)
	{
		int v = 0;
		while (q -- > 0) {
			v <<= 1;
			if ((cc & 1) != 0)
				v ++;
			cc >>>= 1;
		}
		return v;
	}
}
