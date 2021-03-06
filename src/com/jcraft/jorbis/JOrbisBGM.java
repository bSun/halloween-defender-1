/* -*-mode:java; c-basic-offset:2; indent-tabs-mode:nil -*- */
/* JOrbisPlayer -- pure Java Ogg Vorbis player
 *
 * Copyright (C) 2000 ymnk, JCraft,Inc.
 *
 * Written by: 2000 ymnk<ymnk@jcraft.com>
 * 
 * Stripped down by Adam Gashlin
 *
 * Many thanks to 
 *   Monty <monty@xiph.org> and 
 *   The XIPHOPHORUS Company http://www.xiph.org/ .
 * JOrbis has been based on their awesome works, Vorbis codec and
 * JOrbisPlayer depends on JOrbis.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */

package com.jcraft.jorbis;

import java.util.*;
import java.net.*;
import java.io.*;
import java.applet.*;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionEvent;

//import com.jcraft.jorbis.*;
import com.jcraft.jogg.*;

import javax.sound.sampled.*;
import javax.swing.JButton;

public class JOrbisBGM implements Runnable {

	private static final long serialVersionUID=1L;

	boolean running_as_applet=true;

	Thread player=null;
	InputStream bitStream=null;

	static final int BUFSIZE=4096*2;
	static int convsize=BUFSIZE*2;
	static byte[] convbuffer=new byte[convsize];

	private int RETRY=3;
	int retry=RETRY;

	SyncState oy;
	StreamState os;
	Page og;
	Packet op;
	Info vi;
	Comment vc;
	DspState vd;
	Block vb;

	byte[] buffer=null;
	int bytes=0;

	int format;
	int rate=0;
	int channels=0;
	int left_vol_scale=100;
	int right_vol_scale=100;
	URL sourceURL=null;
	SourceDataLine outputLine=null;
	String current_source=null;

	int frameSizeInBytes;
	int bufferLengthInBytes;

	boolean playonstartup=false;
	
	public void set_URL(URL url){
		sourceURL = url;
	}
	
	void init_jorbis(){
		oy=new SyncState();
		os=new StreamState();
		og=new Page();
		op=new Packet();

		vi=new Info();
		vc=new Comment();
		vd=new DspState();
		vb=new Block(vd);

		buffer=null;
		bytes=0;

		oy.init();
	}

	SourceDataLine getOutputLine(int channels, int rate){
		if(outputLine==null||this.rate!=rate||this.channels!=channels){
			if(outputLine!=null){
				outputLine.drain();
				outputLine.stop();
				outputLine.close();
			}
			init_audio(channels, rate);
			outputLine.start();
		}
		return outputLine;
	}

	void init_audio(int channels, int rate){
		try{
			//ClassLoader originalClassLoader=null;
			//try{
				//  originalClassLoader=Thread.currentThread().getContextClassLoader();
			//  Thread.currentThread().setContextClassLoader(ClassLoader.getSystemClassLoader());
			//}
			//catch(Exception ee){
			//  System.out.println(ee);
			//}
			AudioFormat audioFormat=new AudioFormat((float)rate, 16, channels, true, // PCM_Signed
					false // littleEndian
					);
			DataLine.Info info=new DataLine.Info(SourceDataLine.class, audioFormat,
					AudioSystem.NOT_SPECIFIED);
			if(!AudioSystem.isLineSupported(info)){
				//System.out.println("Line " + info + " not supported.");
				return;
			}

			try{
				outputLine=(SourceDataLine)AudioSystem.getLine(info);
				//outputLine.addLineListener(this);
				outputLine.open(audioFormat);
			}
			catch(LineUnavailableException ex){
				System.out.println("Unable to open the sourceDataLine: "+ex);
				return;
			}
			catch(IllegalArgumentException ex){
				System.out.println("Illegal Argument: "+ex);
				return;
			}

			frameSizeInBytes=audioFormat.getFrameSize();
			int bufferLengthInFrames=outputLine.getBufferSize()/frameSizeInBytes/2;
			bufferLengthInBytes=bufferLengthInFrames*frameSizeInBytes;

			//if(originalClassLoader!=null)
			//  Thread.currentThread().setContextClassLoader(originalClassLoader);

			this.rate=rate;
			this.channels=channels;
		}
		catch(Exception ee){
			System.out.println(ee);
		}
	}

	public void run(){
		Thread me=Thread.currentThread();
		player=me;
		while(true){
			bitStream=selectSource(sourceURL);
			if(bitStream!=null){
				play_stream(me);
			}
			else {
				break;
			}
			if(player!=me){
				break;
			}
			bitStream=null;
		}
		player=null;
		System.err.println("JOrbisBGM::run ends");
	}

	private void play_stream(Thread me){

		boolean chained=false;

		init_jorbis();

		retry=RETRY;

		//System.out.println("play_stream>");

		loop: while(true){
			int eos=0;

			int index=oy.buffer(BUFSIZE);
			buffer=oy.data;
			try{
				bytes=bitStream.read(buffer, index, BUFSIZE);
			}
			catch(Exception e){
				System.err.println(e);
				return;
			}
			oy.wrote(bytes);

			if(chained){ //
				chained=false; //   
			} //
			else{ //
				if(oy.pageout(og)!=1){
					if(bytes<BUFSIZE)
						break;
					System.err.println("Input does not appear to be an Ogg bitstream.");
					return;
				}
			} //
			os.init(og.serialno());
			os.reset();

			vi.init();
			vc.init();

			if(os.pagein(og)<0){
				// error; stream version mismatch perhaps
				System.err.println("Error reading first page of Ogg bitstream data.");
				return;
			}

			retry=RETRY;

			if(os.packetout(op)!=1){
				// no page? must not be vorbis
				System.err.println("Error reading initial header packet.");
				break;
				//      return;
			}

			if(vi.synthesis_headerin(vc, op)<0){
				// error case; not a vorbis header
				System.err
				.println("This Ogg bitstream does not contain Vorbis audio data.");
				return;
			}

			int i=0;

			while(i<2){
				while(i<2){
					int result=oy.pageout(og);
					if(result==0)
						break; // Need more data
					if(result==1){
						os.pagein(og);
						while(i<2){
							result=os.packetout(op);
							if(result==0)
								break;
							if(result==-1){
								System.err.println("Corrupt secondary header.  Exiting.");
								//return;
								break loop;
							}
							vi.synthesis_headerin(vc, op);
							i++;
						}
					}
				}

				index=oy.buffer(BUFSIZE);
				buffer=oy.data;
				try{
					bytes=bitStream.read(buffer, index, BUFSIZE);
				}
				catch(Exception e){
					System.err.println(e);
					return;
				}
				if(bytes==0&&i<2){
					System.err.println("End of file before finding all Vorbis headers!");
					return;
				}
				oy.wrote(bytes);
			}

			{
				byte[][] ptr=vc.user_comments;

				for(int j=0; j<ptr.length; j++){
					if(ptr[j]==null)
						break;
					System.err
					.println("Comment: "+new String(ptr[j], 0, ptr[j].length-1));
				}
				System.err.println("Bitstream is "+vi.channels+" channel, "+vi.rate
						+"Hz");
				System.err.println("Encoded by: "
						+new String(vc.vendor, 0, vc.vendor.length-1)+"\n");
			}

			convsize=BUFSIZE/vi.channels;

			vd.synthesis_init(vi);
			vb.init(vd);

			float[][][] _pcmf=new float[1][][];
			int[] _index=new int[vi.channels];

			getOutputLine(vi.channels, vi.rate);

			while(eos==0){
				while(eos==0){

					if(player!=me){
						try{
							bitStream.close();
							outputLine.drain();
							outputLine.stop();
							outputLine.close();
							outputLine=null;
						}
						catch(Exception ee){
						}
						return;
					}

					int result=oy.pageout(og);
					if(result==0)
						break; // need more data
						if(result==-1){ // missing or corrupt data at this page position
							//	    System.err.println("Corrupt or missing data in bitstream; continuing...");
						}
						else{
							os.pagein(og);

							if(og.granulepos()==0){ //
								chained=true; //
								eos=1; // 
								break; //
							} //

							while(true){
								result=os.packetout(op);
								if(result==0)
									break; // need more data
								if(result==-1){ // missing or corrupt data at this page position
									// no reason to complain; already complained above

									//System.err.println("no reason to complain; already complained above");
								}
								else{
									// we have a packet.  Decode it
									int samples;
									if(vb.synthesis(op)==0){ // test for success!
										vd.synthesis_blockin(vb);
									}
									while((samples=vd.synthesis_pcmout(_pcmf, _index))>0){
										float[][] pcmf=_pcmf[0];
										int bout=(samples<convsize ? samples : convsize);

										// convert doubles to 16 bit signed ints (host order) and
										// interleave
										for(i=0; i<vi.channels; i++){
											int ptr=i*2;
											//int ptr=i;
											int mono=_index[i];
											for(int j=0; j<bout; j++){
												int val=(int)(pcmf[i][mono+j]*32767.);
												if(val>32767){
													val=32767;
												}
												if(val<-32768){
													val=-32768;
												}
												if(val<0)
													val=val|0x8000;
												convbuffer[ptr]=(byte)(val);
												convbuffer[ptr+1]=(byte)(val>>>8);
												ptr+=2*(vi.channels);
											}
										}
										outputLine.write(convbuffer, 0, 2*vi.channels*bout);
										vd.synthesis_read(bout);
									}
								}
							}
							if(og.eos()!=0)
								eos=1;
						}
				}

				if(eos==0){
					index=oy.buffer(BUFSIZE);
					buffer=oy.data;
					try{
						bytes=bitStream.read(buffer, index, BUFSIZE);
					}
					catch(Exception e){
						System.err.println(e);
						return;
					}
					if(bytes==-1){
						break;
					}
					oy.wrote(bytes);
					if(bytes==0)
						eos=1;
				}
			}

			os.clear();
			vb.clear();
			vd.clear();
			vi.clear();
		}

		oy.clear();

		try{
			if(bitStream!=null)
				bitStream.close();
		}
		catch(Exception e){
		}
	}

	public void stop(){
		if(player==null){
			try{
				outputLine.drain();
				outputLine.stop();
				outputLine.close();
				outputLine=null;
				if(bitStream!=null)
					bitStream.close();
			}
			catch(Exception e){
			}
		}
		player=null;
	}

	InputStream selectSource(URL url){
		InputStream is=null;
		URLConnection urlc=null;
		try{
			urlc=url.openConnection();
			is=urlc.getInputStream();
			current_source=url.getProtocol()+"://"+url.getHost()+":"+url.getPort()
					+url.getFile();
		}
		catch(Exception ee){
			System.err.println(ee);
		}

		if(is==null)
			return null;

		System.out.println("Select: "+url);

		int i=0;
		String s=null;
		String t=null;
		while(urlc!=null&&true){
			s=urlc.getHeaderField(i);
			t=urlc.getHeaderFieldKey(i);
			if(s==null)
				break;
			i++;
			// doing nothing just now...
		}
		return is;
	}
}
