/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005-2009 Albert Cardona and Rodney Douglas.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA. 

You may contact Albert Cardona at acardona at ini.phys.ethz.ch
Institute of Neuroinformatics, University of Zurich / ETH, Switzerland.
**/

package ini.trakem2.persistence;

import ini.trakem2.utils.IJError;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.gui.YesNoCancelDialog;
import ij.io.DirectoryChooser;
import ij.io.FileInfo;
import ij.io.FileSaver;
import ij.io.Opener;
import ij.io.OpenDialog;
import ij.io.TiffEncoder;
import ij.plugin.filter.PlugInFilter;
import ij.plugin.ContrastEnhancer;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.FloatProcessor;
import ij.process.StackProcessor;
import ij.process.StackStatistics;
import ij.process.ImageStatistics;
import ij.process.ColorProcessor;
import ij.measure.Calibration;
import ij.measure.Measurements;

import ini.trakem2.Project;
import ini.trakem2.display.AreaList;
import ini.trakem2.display.Ball;
import ini.trakem2.display.DLabel;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.DisplayablePanel;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Pipe;
import ini.trakem2.display.Polyline;
import ini.trakem2.display.Profile;
import ini.trakem2.display.Region;
import ini.trakem2.display.Selection;
import ini.trakem2.display.Stack;
import ini.trakem2.display.YesNoDialog;
import ini.trakem2.display.ZDisplayable;
import ini.trakem2.tree.*;
import ini.trakem2.utils.*;
import ini.trakem2.io.*;
import ini.trakem2.imaging.*;
import ini.trakem2.ControlWindow;

import javax.swing.tree.*;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import java.awt.Color;
import java.awt.Component;
import java.awt.Checkbox;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.geom.Area;
import java.awt.geom.AffineTransform;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Vector;
import java.util.LinkedHashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.zip.Inflater;
import java.util.zip.Deflater;

import javax.swing.JMenu;

import mpi.fruitfly.math.datastructures.FloatArray2D;
import mpi.fruitfly.registration.ImageFilter;
import mpi.fruitfly.general.MultiThreading;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import loci.formats.ChannelSeparator;
import loci.formats.FormatException;
import loci.formats.IFormatReader;

/** Handle all data-related issues with a virtualization engine, including load/unload and saving, saving as and overwriting. */
abstract public class Loader {

	// Only one thread at a time is to use the connection and cache
	protected final Object db_lock = new Object();
	private boolean db_busy = false;

	protected Opener opener = new Opener();

	protected final int MAX_RETRIES = 3;

	/** Keep track of whether there are any unsaved changes.*/
	protected boolean changes = false;
	
	final private AtomicLong nextTempId = new AtomicLong( -1 );


	static public final int ERROR_PATH_NOT_FOUND = Integer.MAX_VALUE;

	/** Whether incremental garbage collection is enabled. */
	/*
	static protected final boolean Xincgc = isXincgcSet();

	static protected final boolean isXincgcSet() {
		String[] args = IJ.getInstance().getArgs();
		for (int i=0; i<args.length; i++) {
			if ("-Xingc".equals(args[i])) return true;
		}
		return false;
	}
	*/

	static public final IndexColorModel GRAY_LUT = makeGrayLut();

	static public final IndexColorModel makeGrayLut() {
		final byte[] r = new byte[256];
		final byte[] g = new byte[256];
		final byte[] b = new byte[256];
		for (int i=0; i<256; i++) {
			r[i]=(byte)i;
			g[i]=(byte)i;
			b[i]=(byte)i;
		}
		return new IndexColorModel(8, 256, r, g, b);
	}


	protected final Set<Displayable> hs_unloadable = Collections.synchronizedSet(new HashSet<Displayable>());

	static public final BufferedImage NOT_FOUND = new BufferedImage(10, 10, BufferedImage.TYPE_BYTE_INDEXED, Loader.GRAY_LUT);
	static {
		Graphics2D g = NOT_FOUND.createGraphics();
		g.setColor(Color.white);
		g.drawRect(1, 1, 8, 8);
		g.drawLine(3, 3, 7, 7);
		g.drawLine(7, 3, 3, 7);
	}

	static public final BufferedImage REGENERATING = new BufferedImage(10, 10, BufferedImage.TYPE_BYTE_INDEXED, Loader.GRAY_LUT);
	static {
		Graphics2D g = REGENERATING.createGraphics();
		g.setColor(Color.white);
		g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 8));
		g.drawString("R", 1, 9);
	}

	/** Returns true if the awt is a signaling image like NOT_FOUND or REGENERATING. */
	static public boolean isSignalImage(final Image awt) {
		return REGENERATING == awt || NOT_FOUND == awt;
	}

	// the cache: shared, for there is only one JVM! (we could open a second one, and store images there, and transfer them through sockets)


	// What I need is not provided: a LinkedHashMap with a method to do 'removeFirst' or remove(0) !!! To call my_map.entrySet().iterator() to delete the the first element of a LinkedHashMap is just too much calling for an operation that has to be blazing fast. So I create a double list setup with arrays. The variables are not static because each loader could be connected to a different database, and each database has its own set of unique ids. Memory from other loaders is free by the releaseOthers(double) method.
	transient protected CacheImagePlus imps = new CacheImagePlus(50);
	transient protected CacheImageMipMaps mawts = new CacheImageMipMaps(50);

	static transient protected Vector<Loader> v_loaders = null; // Vector: synchronized

	protected Loader() {
		// register
		if (null == v_loaders) v_loaders = new Vector<Loader>();
		v_loaders.add(this);
		if (!ControlWindow.isGUIEnabled()) {
			opener.setSilentMode(true);
		}

		// debug: report cache status every ten seconds
		/*
		final Loader lo = this;
		new Thread() {
			public void run() {
				setPriority(Thread.NORM_PRIORITY);
				while (true) {
					try { Thread.sleep(1000); } catch (InterruptedException ie) {}
					synchronized(db_lock) {
						lock();
						//if (!v_loaders.contains(lo)) {
						//	unlock();
						//	break;
						//} // TODO BROKEN: not registered!
						Utils.log2("CACHE: \n\timps: " + imps.size() + "\n\tmawts: " + mawts.size());
						mawts.debug();
						unlock();
					}
				}
			}
		}.start();
		*/

		Utils.log2("MAX_MEMORY: " + max_memory);
	}

	/** When the loader has completed its initialization, it should return true on this method. */
	abstract public boolean isReady();

	/** To be called within a synchronized(db_lock) */
	protected final void lock() {
		//Utils.printCaller(this, 7);
		while (db_busy) { try { db_lock.wait(); } catch (InterruptedException ie) {} }
		db_busy = true;
	}

	/** To be called within a synchronized(db_lock) */
	protected final void unlock() {
		//Utils.printCaller(this, 7);
		if (db_busy) {
			db_busy = false;
			db_lock.notifyAll();
		}
	}

	/** Release all memory and unregister itself. Child Loader classes should call this method in their destroy() methods. */
	synchronized public void destroy() {
		if (null != IJ.getInstance() && IJ.getInstance().quitting()) {
			return; // no need to do anything else
		}
		Utils.showStatus("Releasing all memory ...", false);
		destroyCache();
		Project p = Project.findProject(this);
		if (null != v_loaders) {
			v_loaders.remove(this); // sync issues when deleting two loaders consecutively
			if (0 == v_loaders.size()) v_loaders = null;
		}
		
		exec.shutdownNow();
		guiExec.quit();
	}

	/**Retrieve next id from a sequence for a new DBObject to be added.*/
	abstract public long getNextId();

	/**Retrieve next id from a sequence for a temporary Object to be added. Is negative.*/
	public long getNextTempId()
	{
		return nextTempId.getAndDecrement();
	}

	/** Ask for the user to provide a template XML file to extract a root TemplateThing. */
	public TemplateThing askForXMLTemplate(Project project) {
		// ask for an .xml file or a .dtd file
		//fd.setFilenameFilter(new XMLFileFilter(XMLFileFilter.BOTH));
		String user = System.getProperty("user.name");
		OpenDialog od = new OpenDialog("Select XML Template",
						OpenDialog.getDefaultDirectory(),
						null);
		String filename = od.getFileName();
		if (null == filename || filename.toLowerCase().startsWith("null")) return null;
		// if there is a path, read it out if possible
		String dir = od.getDirectory();
		if (null == dir) return null;
		if (IJ.isWindows()) dir = dir.replace('\\', '/');
		if (!dir.endsWith("/")) dir += "/";
		TemplateThing[] roots = DTDParser.extractTemplate(dir + filename);
		if (null == roots || roots.length < 1) return null;
		if (roots.length > 1) {
			Utils.showMessage("Found more than one root.\nUsing first root only.");
		}
		return roots[0];
	}

	private int temp_snapshots_mode = 0;

	public void startLargeUpdate() {
		LayerSet ls = Project.findProject(this).getRootLayerSet();
		temp_snapshots_mode = ls.getSnapshotsMode();
		if (2 != temp_snapshots_mode) ls.setSnapshotsMode(2); // disable repainting snapshots
	}

	public void commitLargeUpdate() {
		Project.findProject(this).getRootLayerSet().setSnapshotsMode(temp_snapshots_mode);
	}

	public void rollback() {
		Project.findProject(this).getRootLayerSet().setSnapshotsMode(temp_snapshots_mode);
	}

	abstract public double[][][] fetchBezierArrays(long id);

	abstract public ArrayList fetchPipePoints(long id);

	abstract public ArrayList fetchBallPoints(long id);

	abstract public Area fetchArea(long area_list_id, long layer_id);

	/* GENERIC, from DBObject calls */
	abstract public boolean addToDatabase(DBObject ob);

	abstract public boolean updateInDatabase(DBObject ob, String key);

	abstract public boolean updateInDatabase(DBObject ob, Set<String> keys);

	abstract public boolean removeFromDatabase(DBObject ob);

	/* Reflection would be the best way to do all above; when it's about and 'id', one only would have to check whether the field in question is a BIGINT and the object given a DBObject, and call getId(). Such an approach demands, though, perfect matching of column names with class field names. */

	public void addCrossLink(long project_id, long id1, long id2) {}

	/** Remove a link between two objects. Returns true always in this empty method. */
	public boolean removeCrossLink(long id1, long id2) { return true; }

	/** Add to the cache, or if already there, make it be the last (to be flushed the last). */
	public void cache(final Displayable d, final ImagePlus imp) {
		synchronized (db_lock) {
			lock();
			final long id = d.getId(); // each Displayable has a unique id for each database, not for different databases, that's why the cache is NOT shared.
			if (Patch.class == d.getClass()) {
				unlock();
				cache((Patch)d, imp);
				return;
			} else {
				Utils.log("Loader.cache: don't know how to cache: " + d);
			}
			unlock();
		}
	}

	public void cache(final Patch p, final ImagePlus imp) {
		if (null == imp || null == imp.getProcessor()) return;
		synchronized (db_lock) {
			lock();
			final long id = p.getId();
			final ImagePlus cached = imps.get(id);
			if (null == cached
			 || cached != imp
			 || imp.getProcessor().getPixels() != cached.getProcessor().getPixels()
			) {
				imps.put(id, imp);
			} else {
				imps.get(id); // send to the end
			}
			unlock();
		}
	}

	/** Cache any ImagePlus, as long as a unique id is assigned to it there won't be problems; you can obtain a unique id from method getNextId() .*/
	public void cacheImagePlus(long id, ImagePlus imp) {
		synchronized (db_lock) {
			lock();
			imps.put(id, imp); // TODO this looks totally unnecessary
			unlock();
		}
	}

	public void decacheImagePlus(long id) {
		synchronized (db_lock) {
			lock();
			ImagePlus imp = imps.remove(id);
			flush(imp);
			unlock();
		}
	}

	public void decacheImagePlus(long[] id) {
		synchronized (db_lock) {
			lock();
			for (int i=0; i<id.length; i++) {
				ImagePlus imp = imps.remove(id[i]);
				flush(imp);
			}
			unlock();
		}
	}

       /** Retrieves a zipped ImagePlus from the given InputStream. The stream is not closed and must be closed elsewhere. No error checking is done as to whether the stream actually contains a zipped tiff file. */
       protected ImagePlus unzipTiff(InputStream i_stream, String title) {
               ImagePlus imp;
               try {
                       // Reading a zipped tiff file in the database


                       /* // works but not faster
                       byte[] bytes = null;
                       // new style: RAM only
                       ByteArrayOutputStream out = new ByteArrayOutputStream();
                       byte[] buf = new byte[4096];
                       int len;
                       int length = 0;
                       while (true) {
                               len = i_stream.read(buf);
                               if (len<0) break;
                               length += len;
                               out.write(buf, 0, len);
                       }
                       Inflater infl = new Inflater();
                       infl.setInput(out.toByteArray(), 0, length);
                       int buflen = length + length;
                       buf = new byte[buflen]; //short almost for sure
                       int offset = 0;
                       ArrayList al = new ArrayList();
                       while (true) {
                               len = infl.inflate(buf, offset, buf.length);
                               al.add(buf);
                               if (0 == infl.getRemaining()) break;
                               buf = new byte[length*2];
                               offset += len;
                       }
                       infl.end();
                       byte[][] b = new byte[al.size()][];
                       al.toArray(b);
                       int blength = buflen * (b.length -1) + len; // the last may be shorter
                       bytes = new byte[blength];
                       for (int i=0; i<b.length -1; i++) {
                               System.arraycopy(b[i], 0, bytes, i*buflen, buflen);
                       }
                       System.arraycopy(b[b.length-1], 0, bytes, buflen * (b.length-1), len);
                       */


                       //OLD, creates tmp file (archive style)
                       ZipInputStream zis = new ZipInputStream(i_stream);
                       ByteArrayOutputStream out = new ByteArrayOutputStream();
                       byte[] buf = new byte[4096]; //copying savagely from ImageJ's Opener.openZip()
                       ZipEntry entry = zis.getNextEntry(); // I suspect this is needed as an iterator
                       int len;
                       while (true) {
                               len = zis.read(buf);
                               if (len<0) break;
                               out.write(buf, 0, len);
                       }
                       zis.close();
                       byte[] bytes = out.toByteArray();

                       ij.IJ.redirectErrorMessages();
                       imp = opener.openTiff(new ByteArrayInputStream(bytes), title);

                       //old
                       //ij.IJ.redirectErrorMessages();
                       //imp = new Opener().openTiff(i_stream, title);
               } catch (Exception e) {
                       IJError.print(e);
                       return null;
               }
               return imp;
       }

	///////////////////

	static protected final Runtime RUNTIME = Runtime.getRuntime();

	static public final long getCurrentMemory() {
		// totalMemory() is the amount of current JVM heap allocation, whether it's being used or not. It may grow over time if -Xms < -Xmx
		return RUNTIME.totalMemory() - RUNTIME.freeMemory(); }

	static public final long getFreeMemory() {
		// max_memory changes as some is reserved by image opening calls
		return max_memory - getCurrentMemory(); }

	/** Maximum vailable memory, in bytes. */
	static private final long MAX_MEMORY = RUNTIME.maxMemory() - 128000000; // 128 M always free
	/** Really available maximum memory, in bytes.
	 * This value can only be edited under synchronized MAXMEMLOCK.
	 * I could use an AtomicLong, but why the overhead? It's private to Loader. */
	static private long max_memory = MAX_MEMORY;
	static private final Object MAXMEMLOCK = new Object();

	/** Use this method to reserve a chunk of memory (With a negative value) or to return it to the pool (with a positive value.) */
	static protected void alterMaxMem(final long n_bytes) {
		synchronized (MAXMEMLOCK) {
			if (max_memory + n_bytes > MAX_MEMORY) max_memory = MAX_MEMORY; // paranoid programming
			else max_memory += n_bytes;
		}
	}

	/** Measure whether there are at least 'n_bytes' free. */
	static final protected boolean enoughFreeMemory(final long n_bytes) {
		long free = getFreeMemory();
		if (free < n_bytes) {
			return false; }
		//if (Runtime.getRuntime().freeMemory() < n_bytes + MIN_FREE_BYTES) return false;
		return n_bytes < max_memory - getCurrentMemory();
	}

	public final boolean releaseToFit(final int width, final int height, final int type, float factor) {
		long bytes = width * height;
		switch (type) {
			case ImagePlus.GRAY32:
				bytes *= 5; // 4 for the FloatProcessor, and 1 for the generated pixels8 to make an image
				if (factor < 4) factor = 4; // for Open_MRC_Leginon ... TODO this is unnecessary in all other cases
				break;
			case ImagePlus.COLOR_RGB:
				bytes *= 4;
				break;
			case ImagePlus.GRAY16:
				bytes *= 3; // 2 for the ShortProcessor, and 1 for the pixels8
				break;
			default: // times 1
				break;
		}
		return releaseToFit((long)(bytes*factor));
	}

	/** Release enough memory so that as many bytes as passed as argument can be loaded. */
	public final boolean releaseToFit(final long n_bytes) {
		if (n_bytes > max_memory) {
			Utils.log("WARNING: Can't fit " + n_bytes + " bytes in memory.");
			// Try anyway
			releaseAll();
			return false;
		}
		if (enoughFreeMemory(n_bytes)) return true;
		boolean result = true;
		synchronized (db_lock) {
			lock();
			result = releaseToFit2(n_bytes);
			unlock();
		}
		return result;
	}

	// Like releaseToFit but non-locking; calls releaseToFit2
	protected final boolean releaseToFit3(long n_bytes) {
		if (enoughFreeMemory(n_bytes)) return true;
		boolean result = releaseToFit2(n_bytes);
		return result;
	}

	// non-locking version
	protected final boolean releaseToFit2(long n_bytes) {
		//if (enoughFreeMemory(n_bytes)) return true;
		if (releaseMemory2(n_bytes) >= n_bytes) return true; // Java will free on its own if it has to
		// else, wait for GC
		int iterations = 30;

		while (iterations > 0) {
			if (0 == imps.size() && 0 == mawts.size()) {
				// wait for GC ...
				System.gc();
				try { Thread.sleep(300); } catch (InterruptedException ie) {}
			}
			if (enoughFreeMemory(n_bytes)) return true;
			iterations--;
		}
		return true;
	}

	/** This method tries to cope with the lack of real time garbage collection in java (that is, lack of predictable time for memory release). */
	public final int runGC() {
		//Utils.printCaller("runGC", 4);
		final long initial = IJ.currentMemory();
		long now = initial;
		final int max = 7;
		long sleep = 50; // initial value
		int iterations = 0;
		Utils.showStatus("Clearing memory...");
		do {
			//Runtime.getRuntime().runFinalization(); // enforce it
			System.gc();
			Thread.yield();
			try { Thread.sleep(sleep); } catch (InterruptedException ie) {}
			sleep += sleep; // incremental
			now = IJ.currentMemory();
			Utils.log2("\titer " + iterations + "  initial: " + initial  + " now: " + now);
			Utils.log2("\t  mawts: " + mawts.size() + "  imps: " + imps.size());
			iterations++;
		} while (now >= initial && iterations < max);
		Utils.showStatus("Memory cleared.");
		return iterations + 1;
	}

	static public final void runGCAll() {
		Loader[] lo = new Loader[v_loaders.size()];
		v_loaders.toArray(lo);
		for (int i=0; i<lo.length; i++) {
			lo[i].runGC();
		}
	}

	static public void printCacheStatus() {
		Loader[] lo = new Loader[v_loaders.size()];
		v_loaders.toArray(lo);
		for (int i=0; i<lo.length; i++) {
			Utils.log2("Loader " + i + " : mawts: " + lo[i].mawts.size() + "  imps: " + lo[i].imps.size());
		}
	}

	/** The minimal number of memory bytes that should always be free. */
	public static long MIN_FREE_BYTES = computeDesirableMinFreeBytes();

	/** 100 Mb per processor, which is a bit more than 67 Mb, the size of a 32-bit 4096x4096 image. */
	private static long computeDesirableMinFreeBytes() {
		long f = 150000000 * Runtime.getRuntime().availableProcessors();
		if (f > max_memory / 2) {
			Utils.logAll("WARNING you are operating with low memory\n  considering the number of CPU cores.\n  Please restart with a higher -Xmx value.");
			return max_memory / 2;
		}
		return f > max_memory / 2 ?
			max_memory / 2
		      : f;
	}

	/** If the number of minimally free memory bytes (100 Mb times the number of CPU cores) is too low for your (giant) images, set it to a larger value here. */
	public static void setDesirableMinFreeBytes(final long n_bytes) {
		long f = computeDesirableMinFreeBytes();
		long max = IJ.maxMemory();
		if (n_bytes < f) {
			Utils.logAll("Refusing to use " + n_bytes + " as the desirable amount of free memory bytes,\n  considering the lower limit at " + f);
		} else if (n_bytes > max) {
			Utils.logAll("Refusing to use a number of minimally free memory bytes larger than max_memory " + max);
		} else if (n_bytes > max / 2) {
			Utils.logAll("WARNING you are setting a value of minimally free memory bytes larger than half the maximum memory.");
		} else {
			f = n_bytes;
		}
		MIN_FREE_BYTES = f;
		Utils.logAll("Using min free bytes " + MIN_FREE_BYTES + " (max memory: " + max + ")");
	}

	private final long measureSize(final ImagePlus imp) {
		if (null == imp) return 0;
		final long size = imp.getWidth() * imp.getHeight() * imp.getNSlices();
		switch (imp.getType()) {
			case ImagePlus.GRAY16:
				return size * 2 + 100;
			case ImagePlus.GRAY32:
			case ImagePlus.COLOR_RGB:
				return size * 4 + 100; // some overhead, it's 16 but allowing for a lot more
			case ImagePlus.GRAY8:
				return size + 100;
			case ImagePlus.COLOR_256:
				return size + 868; // 100 + 3 * 256 (the LUT)
		}
		return 0;
	}

	/** Returns a lower-bound estimate: as if it was grayscale; plus some overhead. */
	private final long measureSize(final Image img) {
		if (null == img) return 0;
		return img.getWidth(null) * img.getHeight(null) + 100;
	}

	/** Free up to MIN_FREE_BYTES. Locks on db_lock. */
	public final long releaseMemory() {
		synchronized (db_lock) {
			try {
				lock();
				return releaseMemory2();
			} catch (Throwable e) {
				IJError.print(e);
				return 0;
			} finally {
				unlock();
			}
		}
	}

	/** Free up to @param min_free_bytes. Locks on db_lock. */
	public final long releaseMemory(final long min_free_bytes) {
		synchronized (db_lock) {
			try {
				lock();
				return releaseMemory2(min_free_bytes);
			} catch (Throwable e) {
				IJError.print(e);
				return 0;
			} finally {
				unlock();
			}
		}
	}

	/** Free up to MIN_FREE_BYTES. */
	protected final long releaseMemory2() {
		return releaseMemory2(MIN_FREE_BYTES);
	}

	private final long releaseOthers(final long min_free_bytes) {
		if (1 == v_loaders.size()) return 0;
		long released = 0;
		for (final Loader lo : new Vector<Loader>(v_loaders)) {
			if (lo == this) continue;
			released += lo.releaseMemory(min_free_bytes); // locking on the other Loader's db_lock
			if (released >= min_free_bytes) return released;
		}
		return released;
	}

	/** Release as much of the cache as necessary to make at least min_free_bytes free.<br />
	*  The very last thing to remove is the stored awt.Image objects.<br />
	*  Removes one ImagePlus at a time if a == 0, else up to 0 &lt; a &lt;= 1.0 .<br />
	*  NOT locked, however calls must take care of that.<br />
	*/
	protected final long releaseMemory2(long min_free_bytes) {
		if (min_free_bytes < MIN_FREE_BYTES) min_free_bytes = MIN_FREE_BYTES;
		long released = 0;
		int BATCH_SIZE = 5 * Runtime.getRuntime().availableProcessors();
		if (BATCH_SIZE < 10) BATCH_SIZE = 10;
		try {
			int iterations = 0;
			while (released < min_free_bytes) {
				if (enoughFreeMemory(min_free_bytes)) return released;

				iterations++;

				// First from other loaders, if any
				released += releaseOthers(min_free_bytes);
				if (released >= min_free_bytes) return released;

				// Second some ImagePlus
				if (0 != imps.size()) {
					for (int i=0; i<BATCH_SIZE; ) {
						ImagePlus imp = imps.removeFirst();
						if (null == imp) break; // BATCH_SIZE larger than cache
						i += imp.getNSlices(); // a stack will contribute much more
						released += measureSize(imp);
						flush(imp);
					}
					Thread.yield();
					if (released >= min_free_bytes) return released;
				}

				// Third some awts
				if (0 != mawts.size()) {
					for (int i=0; i<BATCH_SIZE; i++) {
						Image mawt = mawts.removeFirst();
						if (null == mawt) break; // BATCH_SIZE larger than cache
						released += measureSize(mawt);
						if (null != mawt) mawt.flush();
					}
					if (released >= min_free_bytes) return released;
				}

				// sanity check:
				if (0 == imps.size() && 0 == mawts.size()) {
					if (0 == clonks.incrementAndGet() % 20) runGC();
					Utils.log2("Loader.releaseMemory: empty cache.");
					// Remove any autotraces
					Polyline.flushTraceCache(Project.findProject(this));
					// in any case, can't release more:
					mawts.gc();
					return released;
				} else if (iterations > 50) {
					runGC();
				}
			}
		} catch (Throwable e) {
			IJError.print(e);
		}
		return released;
	}

	static private final AtomicLong clonks = new AtomicLong();

	static public void releaseAllCaches() {
		for (final Loader lo : new Vector<Loader>(v_loaders)) {
			lo.releaseAll();
		}
	}

	/** Empties the caches. */
	public void releaseAll() {
		synchronized (db_lock) {
			lock();
			try {
				for (ImagePlus imp : imps.removeAll()) {
					flush(imp);
				}
				mawts.removeAndFlushAll();
			} catch (Exception e) {
				IJError.print(e);
			}
			unlock();
		}
	}

	private void destroyCache() {
		synchronized (db_lock) {
			try {
				lock();
				if (null != IJ.getInstance() && IJ.getInstance().quitting()) {
					return;
				}
				if (null != imps) {
					for (ImagePlus imp : imps.removeAll()) {
						flush(imp);
					}
					imps = null;
				}
				if (null != mawts) {
					mawts.removeAndFlushAll();
				}
			} catch (Exception e) {
				unlock();
				IJError.print(e);
			} finally {
				unlock();
			}
		}
	}

	/** Removes from the cache all awt images bond to the given id. */
	public void decacheAWT(final long id) {
		synchronized (db_lock) {
			lock();
			mawts.removeAndFlush(id); // where are my lisp macros! Wrapping any function in a synch/lock/unlock could be done crudely with reflection, but what a pain
			unlock();
		}
	}

	public Image getCachedAWT(final long id, final int level) {
		synchronized (db_lock) {
			try {
				lock();
				return mawts.get(id, level);
			} finally {
				unlock();
			}
		}
	}
	
	public void cacheAWT( final long id, final Image awt) {
		synchronized (db_lock) {
			lock();
			if (null != awt) {
				mawts.put(id, awt, 0);
			}
			unlock();
		}
	} 

	/** Transform mag to nearest scale level that delivers an equally sized or larger image.<br />
	 *  Requires 0 &lt; mag &lt;= 1.0<br />
	 *  Returns -1 if the magnification is NaN or negative or zero.<br />
	 *  As explanation:<br />
	 *  mag = 1 / Math.pow(2, level) <br />
	 *  so that 100% is 0, 50% is 1, 25% is 2, and so on, but represented in values between 0 and 1.
	 */
	static public final int getMipMapLevel(final double mag, final double size) {
		// check parameters
		if (mag > 1) return 0; // there is no level for mag > 1, so use mag = 1
		if (mag <= 0 || Double.isNaN(mag)) {
			Utils.log2("ERROR: mag is " + mag);
			return 0; // survive
		}

		final int level = (int)(0.0001 + Math.log(1/mag) / Math.log(2)); // compensating numerical instability: 1/0.25 should be 2 eaxctly
		final int max_level = getHighestMipMapLevel(size);
		/*
		if (max_level > 6) {
			Utils.log2("ERROR max_level > 6: " + max_level + ", size: " + size);
		}
		*/
		return Math.min(level, max_level);

		/*
		int level = 0;
		double scale;	static private ExecutorService exec = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() ); 
		while (true) {
			scale = 1 / Math.pow(2, level);
			//Utils.log2("scale, mag, level: " + scale + ", " + mag + ", " + level);
			if (Math.abs(scale - mag) < 0.00000001) { //if (scale == mag) { // floating-point typical behaviour
				break;
			} else if (scale < mag) {
				// provide the previous one
				level--;
				break;
			}
			// else, continue search
			level++;
		}
		return level;
		*/
	}

	public static final double maxDim(final Displayable d) {
		return Math.max(d.getWidth(), d.getHeight());
	}

	public boolean isImagePlusCached(final Patch p) {
		synchronized (db_lock) {
			try {
				lock();
				return null != imps.get(p.getId());
			} catch (Exception e) {
				IJError.print(e);
				return false;
			} finally {
				unlock();
			}
		}
	}

	/** Returns true if there is a cached awt image for the given mag and Patch id. */
	public boolean isCached(final Patch p, final double mag) {
		synchronized (db_lock) {
			try {
				lock();
				return mawts.contains(p.getId(), Loader.getMipMapLevel(mag, maxDim(p)));
			} catch (Exception e) {
				IJError.print(e);
				return false;
			} finally {
				unlock();
			}
		}
	}

	public Image getCached(final long id, final int level) {
		Image awt = null;
		synchronized (db_lock) {
			lock();
			awt = mawts.getClosestAbove(id, level);
			unlock();
		}
		return awt;
	}

	/** Above or equal in size. */
	public Image getCachedClosestAboveImage(final Patch p, final double mag) {
		Image awt = null;
		synchronized (db_lock) {
			lock();
			awt = mawts.getClosestAbove(p.getId(), Loader.getMipMapLevel(mag, maxDim(p)));
			unlock();
		}
		return awt;
	}

	/** Below, not equal. */
	public Image getCachedClosestBelowImage(final Patch p, final double mag) {
		Image awt = null;
		synchronized (db_lock) {
			lock();
			awt = mawts.getClosestBelow(p.getId(), Loader.getMipMapLevel(mag, maxDim(p)));
			unlock();
		}
		return awt;
	}

	protected final class ImageLoadingLock extends Lock {
		final String key;
		ImageLoadingLock(final String key) { this.key = key; }
	}

	/** Table of dynamic locks, a single one per Patch if any. */
	private final Hashtable<String,ImageLoadingLock> ht_plocks = new Hashtable<String,ImageLoadingLock>();

	protected final ImageLoadingLock getOrMakeImageLoadingLock(final long id, final int level) {
		final String key = new StringBuffer().append(id).append('.').append(level).toString();
		ImageLoadingLock plock = ht_plocks.get(key);
		if (null != plock) return plock;
		plock = new ImageLoadingLock(key);
		ht_plocks.put(key, plock);
		return plock;
	}
	protected final void removeImageLoadingLock(final ImageLoadingLock pl) {
		ht_plocks.remove(pl.key);
	}

	/** Calls fetchImage(p, mag) unless overriden. */
	public Image fetchDataImage(Patch p, double mag) {
		return fetchImage(p, mag);
	}

	public Image fetchImage(Patch p) {
		return fetchImage(p, 1.0);
	}

	/** Fetch a suitable awt.Image for the given mag(nification).
	 * If the mag is bigger than 1.0, it will return as if was 1.0.
	 * Will return Loader.NOT_FOUND if, err, not found (probably an Exception will print along).
	 */
	public Image fetchImage(final Patch p, double mag) {
		// Below, the complexity of the synchronized blocks is to provide sufficient granularity. Keep in mind that only one thread at at a time can access a synchronized block for the same object (in this case, the db_lock), and thus calling lock() and unlock() is not enough. One needs to break the statement in as many synch blocks as possible for maximizing the number of threads concurrently accessing different parts of this function.

		if (mag > 1.0) mag = 1.0; // Don't want to create gigantic images!
		int level = Loader.getMipMapLevel(mag, maxDim(p));
		int max_level = Loader.getHighestMipMapLevel(p);
		//Utils.log2("level=" + level + "  max_level=" + max_level);
		if (level > max_level) level = max_level;

		// testing:
		// if (level > 0) level--; // passing an image double the size, so it's like interpolating when doing nearest neighbor since the images are blurred with sigma 0.5
		// SLOW, very slow ...

		// find an equal or larger existing pyramid awt
		final long id = p.getId();
		ImageLoadingLock plock = null;

		synchronized (db_lock) {
			lock();
			try {
				if (null == mawts) {
					return NOT_FOUND; // when lazy repainting after closing a project, the awts is null
				}
				if (level >= 0 && isMipMapsEnabled()) {
					// 1 - check if the exact level is cached
					final Image mawt = mawts.get(id, level);
					if (null != mawt) {
						//Utils.log2("returning cached exact mawt for level " + level);
						return mawt;
					}
					//
					releaseMemory2();
					plock = getOrMakeImageLoadingLock(p.getId(), level);
				}
			} catch (Exception e) {
				IJError.print(e);
			} finally {
				unlock();
			}
		}

		Image mawt = null;
		long n_bytes = 0;

		// 2 - check if the exact file is present for the desired level
		if (level >= 0 && isMipMapsEnabled()) {
			synchronized (plock) {
				plock.lock();

				synchronized (db_lock) {
					lock();
					mawt = mawts.get(id, level);
					unlock();
				}
				if (null != mawt) {
					plock.unlock();
					return mawt; // was loaded by a different thread
				}

				// going to load:

				synchronized (db_lock) {
					lock();
					n_bytes = estimateImageFileSize(p, level);
					alterMaxMem(-n_bytes);
					unlock();
				}

				try {
					// Locks on db_lock to release memory when needed
					mawt = fetchMipMapAWT(p, level, n_bytes);
				} catch (Throwable t) {
					IJError.print(t);
					mawt = null;
				}

				synchronized (db_lock) {
					try {
						lock();
						alterMaxMem(n_bytes);
						if (null != mawt) {
							//Utils.log2("returning exact mawt from file for level " + level);
							if (REGENERATING != mawt) {
								mawts.put(id, mawt, level);
								Display.repaintSnapshot(p);
							}
							return mawt;
						}
						// 3 - else, load closest level to it but still giving a larger image
						final int lev = getClosestMipMapLevel(p, level); // finds the file for the returned level, otherwise returns zero
						//Utils.log2("closest mipmap level is " + lev);
						if (lev >= 0) {
							mawt = mawts.getClosestAbove(id, lev);
							boolean newly_cached = false;
							if (null == mawt) {
								// reload existing scaled file
								mawt = fetchMipMapAWT2(p, lev, n_bytes);
								if (null != mawt) {
									mawts.put(id, mawt, lev);
									newly_cached = true; // means: cached was false, now it is
								}
								// else if null, the file did not exist or could not be regenerated or regeneration is off
							}
							//Utils.log2("from getClosestMipMapLevel: mawt is " + mawt);
							if (null != mawt) {
								if (newly_cached) Display.repaintSnapshot(p);
								//Utils.log2("returning from getClosestMipMapAWT with level " + lev);
								return mawt;
							}
						} else if (ERROR_PATH_NOT_FOUND == lev) {
							mawt = NOT_FOUND;
						}
					} catch (Exception e) {
						IJError.print(e);
					} finally {
						removeImageLoadingLock(plock);
						unlock();
						plock.unlock();
					}
				}
			}
		}

		// level is zero or nonsensically lower than zero, or was not found
		//Utils.log2("not found!");

		synchronized (db_lock) {
			try {
				lock();

				// 4 - check if any suitable level is cached (whithout mipmaps, it may be the large image)
				mawt = mawts.getClosestAbove(id, level);
				if (null != mawt) {
					//Utils.log2("returning from getClosest with level " + level);
					return mawt;
				}
			} catch (Exception e) {
				IJError.print(e);
			} finally {
				unlock();
			}
		}

		// 5 - else, fetch the (perhaps) transformed ImageProcessor and make an image from it of the proper size and quality

		if (hs_unloadable.contains(p)) return NOT_FOUND;

		synchronized (db_lock) {
			try {
				lock();
				releaseMemory2();
				plock = getOrMakeImageLoadingLock(p.getId(), level);
			} catch (Exception e) {
				return NOT_FOUND;
			} finally {
				unlock();
			}
		}

		synchronized (plock) {
			try {
				plock.lock();

				// Check if a previous call made it while waiting:
				mawt = mawts.getClosestAbove(id, level);
				if (null != mawt) {
					synchronized (db_lock) {
						lock();
						removeImageLoadingLock(plock);
						unlock();
					}
					return mawt;
				}

				// Else, create the mawt:
				plock.unlock();
				Patch.PatchImage pai = p.createTransformedImage();
				plock.lock();
				if (null != pai && null != pai.target) {
					final ImageProcessor ip = pai.target;
					ip.setMinAndMax(p.getMin(), p.getMax());
					ByteProcessor alpha_mask = pai.mask; // can be null;
					final ByteProcessor outside_mask = pai.outside; // can be null
					if (null == alpha_mask) {
						alpha_mask = outside_mask;
					}
					pai = null;
					if (null != alpha_mask) {
						mawt = createARGBImage(ip.getWidth(), ip.getHeight(),
								       embedAlpha((int[])ip.convertToRGB().getPixels(),
										  (byte[])alpha_mask.getPixels(),
										  null == outside_mask ? null : (byte[])outside_mask.getPixels()));
					} else {
						mawt = ip.createImage();
					}
				}
			} catch (Exception e) {
				Utils.log2("Could not create an image for Patch " + p);
				mawt = null;
			} finally {
				plock.unlock();
			}
		}

		synchronized (db_lock) {
			try {
				lock();
				if (null != mawt) {
					mawts.put(id, mawt, level);
					Display.repaintSnapshot(p);
					//Utils.log2("Created mawt from scratch.");
					return mawt;
				}
			} catch (Exception e) {
				IJError.print(e);
			} finally {
				removeImageLoadingLock(plock);
				unlock();
			}
		}

		return NOT_FOUND;
	}

	/** Returns null.*/
	public ByteProcessor fetchImageMask(final Patch p) {
		return null;
	}

	public String getAlphaPath(final Patch p) {
		return null;
	}

	/** Does nothing unless overriden. */
	public void storeAlphaMask(final Patch p, final ByteProcessor fp) {}

	/** Does nothing unless overriden. */
	public boolean removeAlphaMask(final Patch p) { return false; }

	/** Must be called within synchronized db_lock. */
	private final Image fetchMipMapAWT2(final Patch p, final int level, final long n_bytes) {
		final long size = estimateImageFileSize(p, level);
		alterMaxMem(-size);
		unlock();
		Image mawt = null;
		try {
			mawt = fetchMipMapAWT(p, level, n_bytes); // locks on db_lock
		} catch (Throwable e) {
			IJError.print(e);
		}
		lock();
		alterMaxMem(size);
		return mawt;
	}

	/** Simply reads from the cache, does no reloading at all. If the ImagePlus is not found in the cache, it returns null and the burden is on the calling method to do reconstruct it if necessary. This is intended for the LayerStack. */
	public ImagePlus getCachedImagePlus(final long id) {
		synchronized(db_lock) {
			ImagePlus imp = null;
			lock();
			imp = imps.get(id);
			unlock();
			return imp;
		}
	}

	abstract public ImagePlus fetchImagePlus(Patch p);
	/** Returns null unless overriden. */
	public ImageProcessor fetchImageProcessor(Patch p) { return null; }

	public ImagePlus fetchImagePlus( Stack p ) { return null; }
	
	abstract public Object[] fetchLabel(DLabel label);


	/**Returns the ImagePlus as a zipped InputStream of bytes; the InputStream has to be closed by whoever is calling this method. */
	protected InputStream createZippedStream(ImagePlus imp) throws Exception {
		FileInfo fi = imp.getFileInfo();
		Object info = imp.getProperty("Info");
		if (info != null && (info instanceof String)) {
			fi.info = (String)info;
		}
		if (null == fi.description) {
			fi.description = new ij.io.FileSaver(imp).getDescriptionString();
		}
		//see whether this is a stack or not
		/*	//never the case in my program
		if (fi.nImages > 1) {
			IJ.log("saving a stack!");
			//virtual stacks would be supported? I don't think so because the FileSaver.saveAsTiffStack(String path) doesn't.
			if (fi.pixels == null && imp.getStack().isVirtual()) {
				//don't save it!
				IJ.showMessage("Virtual stacks not supported.");
				return false;
			}
			//setup stack things as in FileSaver.saveAsTiffStack(String path)
			fi.sliceLabels = imp.getStack().getSliceLabels();
		}
		*/
		TiffEncoder te = new TiffEncoder(fi);
		ByteArrayInputStream i_stream = null;
		ByteArrayOutputStream o_bytes = new ByteArrayOutputStream();
		DataOutputStream o_stream = null;
		try {
			/* // works, but not significantly faster and breaks older databases (can't read zipped images properly)
			byte[] bytes = null;
			// compress in RAM
			o_stream = new DataOutputStream(new BufferedOutputStream(o_bytes));
			te.write(o_stream);
			o_stream.flush();
			o_stream.close();
			Deflater defl = new Deflater();
			byte[] unzipped_bytes = o_bytes.toByteArray();
			defl.setInput(unzipped_bytes);
			defl.finish();
			bytes = new byte[unzipped_bytes.length]; // this length *should* be enough
			int length = defl.deflate(bytes);
			if (length < unzipped_bytes.length) {
				byte[] bytes2 = new byte[length];
				System.arraycopy(bytes, 0, bytes2, 0, length);
				bytes = bytes2;
			}
			*/
			// old, creates temp file
			o_bytes = new ByteArrayOutputStream(); // clearing
			ZipOutputStream zos = new ZipOutputStream(o_bytes);
			o_stream = new DataOutputStream(new BufferedOutputStream(zos));
			zos.putNextEntry(new ZipEntry(imp.getTitle()));
			te.write(o_stream);
			o_stream.flush(); //this was missing and was 1) truncating the Path images and 2) preventing the snapshots (which are very small) to be saved at all!!
			o_stream.close(); // this should ensure the flush above anyway. This can work because closing a ByteArrayOutputStream has no effect.
			byte[] bytes = o_bytes.toByteArray();

			//Utils.showStatus("Zipping " + bytes.length + " bytes...", false);
			//Utils.debug("Zipped ImagePlus byte array size = " + bytes.length);
			i_stream = new ByteArrayInputStream(bytes);
		} catch (Exception e) {
			Utils.log("Loader: ImagePlus NOT zipped! Problems at writing the ImagePlus using the TiffEncoder.write(dos) :\n " + e);
			//attempt to cleanup:
			try {
				if (null != o_stream) o_stream.close();
				if (null != i_stream) i_stream.close();
			} catch (IOException ioe) {
				Utils.log("Loader: Attempt to clean up streams failed.");
				IJError.print(ioe);
			}
			return null;
		}
		return i_stream;
	}


	/** A dialog to open a stack, making sure there is enough memory for it. */
	synchronized public ImagePlus openStack() {
		final OpenDialog od = new OpenDialog("Select stack", OpenDialog.getDefaultDirectory(), null);
		String file_name = od.getFileName();
		if (null == file_name || file_name.toLowerCase().startsWith("null")) return null;
		String dir = od.getDirectory().replace('\\', '/');
		if (!dir.endsWith("/")) dir += "/";

		File f = new File(dir + file_name);
		if (!f.exists()) {
			Utils.showMessage("File " + dir + file_name  + " does not exist.");
			return null;
		}
		// avoid opening trakem2 projects
		if (file_name.toLowerCase().endsWith(".xml")) {
			Utils.showMessage("Cannot import " + file_name + " as a stack.");
			return null;
		}
		// estimate file size: assumes an uncompressed tif, or a zipped tif with an average compression ratio of 2.5
		long size = f.length() / 1024; // in megabytes
		if (file_name.length() -4 == file_name.toLowerCase().lastIndexOf(".zip")) {
			size = (long)(size * 2.5); // 2.5 is a reasonable compression ratio estimate based on my experience
		}
		int max_iterations = 15;
		while (enoughFreeMemory(size)) {
			if (0 == max_iterations) {
				// leave it to the Opener class to throw an OutOfMemoryError if so.
				break;
			}
			max_iterations--;
			releaseMemory();
		}
		ImagePlus imp_stack = null;
		try {
			IJ.redirectErrorMessages();
			imp_stack = openImagePlus(f.getCanonicalPath());
		} catch (Exception e) {
			IJError.print(e);
			return null;
		}
		if (null == imp_stack) {
			Utils.showMessage("Can't open the stack.");
			return null;
		} else if (1 == imp_stack.getStackSize()) {
			Utils.showMessage("Not a stack!");
			return null;
		}
		return imp_stack; // the open... command
	}

	public Bureaucrat importSequenceAsGrid(Layer layer) {
		return importSequenceAsGrid(layer, null);
	}

	public Bureaucrat importSequenceAsGrid(final Layer layer, String dir) {
		return importSequenceAsGrid(layer, dir, null);
	}

	/** Open one of the images to find out the dimensions, and get a good guess at what is the desirable scale for doing phase- and cross-correlations with about 512x512 images. */
	private int getCCScaleGuess(final File images_dir, final String[] all_images) {
		try {
			if (null != all_images && all_images.length > 0) {
				Utils.showStatus("Opening one image ... ", false);
				String sdir = images_dir.getAbsolutePath().replace('\\', '/');
				if (!sdir.endsWith("/")) sdir += "/";
				IJ.redirectErrorMessages();
				ImagePlus imp = openImagePlus(sdir + all_images[0]);
				if (null != imp) {
					int w = imp.getWidth();
					int h = imp.getHeight();
					flush(imp);
					imp = null;
					int cc_scale = (int)((512.0 / (w > h ? w : h)) * 100);
					if (cc_scale > 100) return 100;
					return cc_scale;
				}
			}
		} catch (Exception e) {
			Utils.log2("Could not get an estimate for the optimal scale.");
		}
		return 25;
	}

	/** Import a sequence of images as a grid, and put them in the layer. If the directory (@param dir) is null, it'll be asked for. The image_file_names can be null, and in any case it's only the names, not the paths. */
	public Bureaucrat importSequenceAsGrid(final Layer first_layer, String dir, final String[] image_file_names) {
		String[] all_images = null;
		String file = null; // first file
		File images_dir = null;

		if (null != dir && null != image_file_names) {
			all_images = image_file_names;
			images_dir = new File(dir);
		} else if (null == dir) {
			String[] dn = Utils.selectFile("Select first image");
			if (null == dn) return null;
			dir = dn[0];
			file = dn[1];
			images_dir = new File(dir);
		} else {
			images_dir = new File(dir);
			if (!(images_dir.exists() && images_dir.isDirectory())) {
				Utils.showMessage("Something went wrong:\n\tCan't find directory " + dir);
				return null;
			}
		}
		if (null == image_file_names) all_images = images_dir.list(new ini.trakem2.io.ImageFileFilter("", null));

		if (null == file && all_images.length > 0) {
			file = all_images[0];
		}

		int n_max = all_images.length;

		// reasonable estimate
		int side = (int)Math.floor(Math.sqrt(n_max));

		GenericDialog gd = new GenericDialog("Conventions");
		gd.addStringField("file_name_matches: ", "");
		gd.addNumericField("first_image: ", 1, 0);
		gd.addNumericField("last_image: ", n_max, 0);
		gd.addCheckbox("Reverse list order", false);
		gd.addNumericField("number_of_rows: ", side, 0);
		gd.addNumericField("number_of_columns: ", side, 0);
		gd.addNumericField("number_of_slices: ", 1, 0);
		gd.addMessage("The top left coordinate for the imported grid:");
		gd.addNumericField("base_x: ", 0, 3);
		gd.addNumericField("base_y: ", 0, 3);
		gd.addMessage("Amount of image overlap, in pixels");
		gd.addNumericField("bottom-top overlap: ", 0, 2); //as asked by Joachim Walter
		gd.addNumericField("left-right overlap: ", 0, 2);
		gd.addCheckbox("link images", false);
		gd.addCheckbox("registration", true);
		StitchingTEM.addStitchingRuleChoice(gd);
		gd.addSlider("tile_overlap (%): ", 1, 100, 10);
		gd.addSlider("cc_scale (%):", 1, 100, getCCScaleGuess(images_dir, all_images));
		gd.addCheckbox("homogenize_contrast", false);
		final Component[] c = {
			(Component)gd.getSliders().get(gd.getSliders().size()-2),
			(Component)gd.getNumericFields().get(gd.getNumericFields().size()-2),
			(Component)gd.getSliders().get(gd.getSliders().size()-1),
			(Component)gd.getNumericFields().get(gd.getNumericFields().size()-1),
			(Component)gd.getChoices().get(gd.getChoices().size()-1)
		};
		// enable the checkbox to control the slider and its associated numeric field:
		Utils.addEnablerListener((Checkbox)gd.getCheckboxes().get(gd.getCheckboxes().size()-2), c, null);
		//gd.addCheckbox("Apply non-linear deformation", false);

		gd.showDialog();

		if (gd.wasCanceled()) return null;

		final String regex = gd.getNextString();
		Utils.log2(new StringBuffer("using regex: ").append(regex).toString()); // avoid destroying backslashes
		int first = (int)gd.getNextNumber();
		if (first < 1) first = 1;
		int last = (int)gd.getNextNumber();
		if (last < 1) last = 1;
		if (last < first) {
			Utils.showMessage("Last is smaller that first!");
			return null;
		}

		final boolean reverse_order = gd.getNextBoolean();

		final int n_rows = (int)gd.getNextNumber();
		final int n_cols = (int)gd.getNextNumber();
		final int n_slices = (int)gd.getNextNumber();
		final double bx = gd.getNextNumber();
		final double by = gd.getNextNumber();
		double bt_overlap = gd.getNextNumber();
		double lr_overlap = gd.getNextNumber();
		final boolean link_images = gd.getNextBoolean();
		final boolean stitch_tiles = gd.getNextBoolean();
		final float cc_percent_overlap = (float)gd.getNextNumber() / 100f;
		final float cc_scale = (float)gd.getNextNumber() / 100f;
		final boolean homogenize_contrast = gd.getNextBoolean();
		final int stitching_rule = gd.getNextChoiceIndex();
		//boolean apply_non_linear_def = gd.getNextBoolean();

		// Ensure tiles overlap if using SIFT
		if (StitchingTEM.FREE_RULE == stitching_rule) {
			if (bt_overlap <= 0) bt_overlap = 1;
			if (lr_overlap <= 0) lr_overlap = 1;
		}

		String[] file_names = null;
		if (null == image_file_names) {
			file_names = images_dir.list(new ini.trakem2.io.ImageFileFilter(regex, null));
			Arrays.sort(file_names); //assumes 001, 002, 003 ... that style, since it does binary sorting of strings
			if (reverse_order) {
				// flip in place
				for (int i=file_names.length/2; i>-1; i--) {
					String tmp = file_names[i];
					int j = file_names.length -1 -i;
					file_names[i] = file_names[j];
					file_names[j] =  tmp;
				}
			}
		} else {
			file_names = all_images;
		}

		if (0 == file_names.length) {
			Utils.showMessage("No images found.");
			return null;
		}
		// check if the selected image is in the list. Otherwise, shift selected image to the first of the included ones.
		boolean found_first = false;
		for (int i=0; i<file_names.length; i++) {
			if (file.equals(file_names[i])) {
				found_first = true;
				break;
			}
		}
		if (!found_first) {
			file = file_names[0];
			Utils.log("Using " + file + " as the reference image for size.");
		}
		// crop list
		if (last > file_names.length) last = file_names.length -1;
		if (first < 1) first = 1;
		if (1 != first || last != file_names.length) {
			Utils.log("Cropping list.");
			String[] file_names2 = new String[last - first + 1];
			System.arraycopy(file_names, first -1, file_names2, 0, file_names2.length);
			file_names = file_names2;
		}
		// should be multiple of rows and cols and slices
		if (file_names.length != n_rows * n_cols * n_slices) {
			Utils.log("ERROR: rows * cols * slices does not match with the number of selected images.");
			Utils.log("n_images:" + file_names.length + "  rows,cols,slices : " + n_rows + "," + n_cols + "," + n_slices + "  total=" + n_rows*n_cols*n_slices);
			return null;
		}

		// I luv java
		final String[] file_names_ = file_names;
		final String dir_ = dir;
		final String file_ = file; // the first file
		final double bt_overlap_ = bt_overlap;
		final double lr_overlap_ = lr_overlap;

		return Bureaucrat.createAndStart(new Worker.Task("Importing 1/" + n_slices, true) {
			public void exec() {
				// Slice up list:
				for (int sl=0; sl<n_slices; sl++) {
					if (Thread.currentThread().isInterrupted() || hasQuitted()) return;
					setTaskName("Importing " + (sl+1) + "/" + n_slices);
					int start = sl * n_rows * n_cols;
					ArrayList cols = new ArrayList();
					for (int i=0; i<n_cols; i++) {
						String[] col = new String[n_rows];
						for (int j=0; j<n_rows; j++) {
							col[j] = file_names_[start + j*n_cols + i];
						}
						cols.add(col);
					}

					Layer layer = 0 == sl ? first_layer
			                                      : first_layer.getParent().getLayer(first_layer.getZ() + first_layer.getThickness() * sl, first_layer.getThickness(), true);

					Bureaucrat b = insertGrid(layer, dir_, file_, n_rows*n_cols, cols, bx, by, bt_overlap_, lr_overlap_, link_images, stitch_tiles, cc_percent_overlap, cc_scale, homogenize_contrast, stitching_rule/*, apply_non_linear_def*/);
					try {
						b.join();
					} catch (InterruptedException ie) {
						b.quit();
					}
				}
			}
		}, first_layer.getProject());
	}

	public Bureaucrat importGrid(Layer layer) {
		return importGrid(layer, null);
	}

	/** Import a grid of images and put them in the layer. If the directory (@param dir) is null, it'll be asked for. */
	public Bureaucrat importGrid(Layer layer, String dir) {
		try {
			String file = null;
			if (null == dir) {
				String[] dn = Utils.selectFile("Select first image");
				if (null == dn) return null;
				dir = dn[0];
				file = dn[1];
			}

		String convention = "cdd"; // char digit digit
		boolean chars_are_columns = true;
		// examine file name
		/*
		if (file.matches("\\A[a-zA-Z]\\d\\d.*")) { // one letter, 2 numbers
			//means:
			//	\A		- beggining of input
			//	[a-zA-Z]	- any letter upper or lower case
			//	\d\d		- two consecutive digits
			//	.*		- any row of chars
			ini_grid_convention = true;
		}
		*/
		// ask for chars->rows, numbers->columns or viceversa
		GenericDialog gd = new GenericDialog("Conventions");
		gd.addStringField("file_name_contains:", "");
		gd.addNumericField("base_x: ", 0, 3);
		gd.addNumericField("base_y: ", 0, 3);
		gd.addMessage("Use: x(any), c(haracter), d(igit)");
		gd.addStringField("convention: ", convention);
		final String[] cr = new String[]{"columns", "rows"};
		gd.addChoice("characters are: ", cr, cr[0]);
		gd.addMessage("[File extension ignored]");

		gd.addNumericField("bottom-top overlap: ", 0, 3); //as asked by Joachim Walter
		gd.addNumericField("left-right overlap: ", 0, 3);
		gd.addCheckbox("link_images", false);
		gd.addCheckbox("registration", false);
		StitchingTEM.addStitchingRuleChoice(gd);
		gd.addSlider("tile_overlap (%): ", 1, 100, 10);
		gd.addSlider("cc_scale (%):", 1, 100, 25);
		gd.addCheckbox("homogenize_contrast", true);
		final Component[] c = {
			(Component)gd.getSliders().get(gd.getSliders().size()-2),
			(Component)gd.getNumericFields().get(gd.getNumericFields().size()-2),
			(Component)gd.getSliders().get(gd.getSliders().size()-1),
			(Component)gd.getNumericFields().get(gd.getNumericFields().size()-1),
			(Component)gd.getChoices().get(gd.getChoices().size()-1)
		};
		// enable the checkbox to control the slider and its associated numeric field:
		Utils.addEnablerListener((Checkbox)gd.getCheckboxes().get(gd.getCheckboxes().size()-1), c, null);
		//gd.addCheckbox("Apply non-linear deformation", false);

		gd.showDialog();
		if (gd.wasCanceled()) {
			return null;
		}
		//collect data
		String regex = gd.getNextString(); // filter away files not containing this tag
		// the base x,y of the whole grid
		double bx = gd.getNextNumber();
		double by = gd.getNextNumber();
		//if (!ini_grid_convention) {
			convention = gd.getNextString().toLowerCase();
		//}
		if (/*!ini_grid_convention && */ (null == convention || convention.equals("") || -1 == convention.indexOf('c') || -1 == convention.indexOf('d'))) { // TODO check that the convention has only 'cdx' chars and also that there is an island of 'c's and of 'd's only.
			Utils.showMessage("Convention '" + convention + "' needs both c(haracters) and d(igits), optionally 'x', and nothing else!");
			return null;
		}
		chars_are_columns = (0 == gd.getNextChoiceIndex());
		double bt_overlap = gd.getNextNumber();
		double lr_overlap = gd.getNextNumber();
		boolean link_images = gd.getNextBoolean();
		boolean stitch_tiles = gd.getNextBoolean();
		float cc_percent_overlap = (float)gd.getNextNumber() / 100f;
		float cc_scale = (float)gd.getNextNumber() / 100f;
		boolean homogenize_contrast = gd.getNextBoolean();
		int stitching_rule = gd.getNextChoiceIndex();
		//boolean apply_non_linear_def = gd.getNextBoolean();

		// Ensure tiles overlap if using SIFT
		if (StitchingTEM.FREE_RULE == stitching_rule) {
			if (bt_overlap <= 0) bt_overlap = 1;
			if (lr_overlap <= 0) lr_overlap = 1;
		}

		//start magic
		//get ImageJ-openable files that comply with the convention
		File images_dir = new File(dir);
		if (!(images_dir.exists() && images_dir.isDirectory())) {
			Utils.showMessage("Something went wrong:\n\tCan't find directory " + dir);
			return null;
		}
		String[] file_names = images_dir.list(new ImageFileFilter(regex, convention));
		if (null == file && file_names.length > 0) {
			// the 'selected' file
			file = file_names[0];
		}
		Utils.showStatus("Adding " + file_names.length + " patches.", false);
		if (0 == file_names.length) {
			Utils.log("Zero files match the convention '" + convention + "'");
			return null;
		}

		// How to: select all files, and order their names in a double array as they should be placed in the Display. Then place them, displacing by offset, and resizing if necessary.
		// gather image files:
		Montage montage = new Montage(convention, chars_are_columns);
		montage.addAll(file_names);
		ArrayList cols = montage.getCols(); // an array of Object[] arrays, of unequal length maybe, each containing a column of image file names
		return insertGrid(layer, dir, file, file_names.length, cols, bx, by, bt_overlap, lr_overlap, link_images, stitch_tiles, cc_percent_overlap, cc_scale, homogenize_contrast, stitching_rule/*, apply_non_linear_def*/);

		} catch (Exception e) {
			IJError.print(e);
		}
		return null;
	}

	/**
	 * @param layer The Layer to inser the grid into
	 * @param dir The base dir of the images to open
	 * @param cols The list of columns, containing each an array of String file names in each column.
	 * @param bx The top-left X coordinate of the grid to insert
	 * @param by The top-left Y coordinate of the grid to insert
	 * @param bt_overlap bottom-top overlap of the images
	 * @param lr_overlap left-right overlap of the images
	 * @param link_images Link images to their neighbors.
	 */
	private Bureaucrat insertGrid(final Layer layer, final String dir_, final String first_image_name, final int n_images, final ArrayList cols, final double bx, final double by, final double bt_overlap, final double lr_overlap, final boolean link_images, final boolean stitch_tiles, final float cc_percent_overlap, final float cc_scale, final boolean homogenize_contrast, final int stitching_rule/*, final boolean apply_non_linear_def*/) {

		// create a Worker, then give it to the Bureaucrat

		Worker worker = new Worker("Inserting grid") {
			public void run() {
				startedWorking();

		try {
			String dir = dir_;
		ArrayList<Patch> al = new ArrayList<Patch>();
		Utils.showProgress(0.0D);
		opener.setSilentMode(true); // less repaints on IJ status bar

		/* If requested, ask for a text file containing the non-linear deformation coefficients
		 * and obtain a NonLinearTransform object and coefficients to apply to all images. */
		/*
		// NOT READY YET
		final NonLinearTransform nlt = apply_non_linear_def ? askForNonLinearTransform() : null;
		final double[][] nlt_coeffs = null != nlt ? nlt.getCoefficients() : null;

		if (apply_non_linear_def && null == nlt) {
			finishedWorking();
			return;
		}
		*/


		int x = 0;
		int y = 0;
		int largest_y = 0;
		ImagePlus img = null;
		// open the selected image, to use as reference for width and height
		if (!enoughFreeMemory(MIN_FREE_BYTES)) releaseMemory();
		dir = dir.replace('\\', '/'); // w1nd0wz safe
		if (!dir.endsWith("/")) dir += "/";
		String path = dir + first_image_name;
		IJ.redirectErrorMessages();
		ImagePlus first_img = openImagePlus(path);
		if (null == first_img) {
			Utils.log("Selected image to open first is null.");
			return;
		}

		if (null == first_img) return;
		final int first_image_width = first_img.getWidth();
		final int first_image_height = first_img.getHeight();
		final int first_image_type = first_img.getType();
		// start
		final Patch[][] pall = new Patch[cols.size()][((String[])cols.get(0)).length];
		int width, height;
		int k = 0; //counter
		boolean auto_fix_all = false;
		boolean ignore_all = false;
		boolean resize = false;
		if (!ControlWindow.isGUIEnabled()) {
			// headless mode: autofix all
			auto_fix_all = true;
			resize = true;
		}

		// Accumulate mipmap generation tasks
		final ArrayList<Future> fus = new ArrayList<Future>();

		startLargeUpdate();
		for (int i=0; i<cols.size(); i++) {
			String[] rows = (String[])cols.get(i);
			if (i > 0) {
				x -= lr_overlap;
			}
			for (int j=0; j<rows.length; j++) {
				if (this.quit) {
					Display.repaint(layer);
					rollback();
					return;
				}
				if (j > 0) {
					y -= bt_overlap;
				}
				// get file name
				String file_name = (String)rows[j];
				path = dir + file_name;
				if (null != first_img && file_name.equals(first_image_name)) {
					img = first_img;
					first_img = null; // release pointer
				} else {
					// open image
					//if (!enoughFreeMemory(MIN_FREE_BYTES)) releaseMemory(); // UNSAFE, doesn't wait for GC
					releaseToFit(first_image_width, first_image_height, first_image_type, 1.5f);
					try {
						IJ.redirectErrorMessages();
						img = openImagePlus(path);
					} catch (OutOfMemoryError oome) {
						printMemState();
						throw oome;
					}
				}
				if (null == img) {
					Utils.log("null image! skipping.");
					pall[i][j] = null;
					continue;
				}

				width = img.getWidth();
				height = img.getHeight();
				int rw = width;
				int rh = height;
				if (width != first_image_width || height != first_image_height) {
					int new_width = first_image_width;
					int new_height = first_image_height;
					if (!auto_fix_all && !ignore_all) {
						GenericDialog gdr = new GenericDialog("Size mismatch!");
						gdr.addMessage("The size of " + file_name + " is " + width + " x " + height);
						gdr.addMessage("but the selected image was " + first_image_width + " x " + first_image_height);
						gdr.addMessage("Adjust to selected image dimensions?");
						gdr.addNumericField("width: ", (double)first_image_width, 0);
						gdr.addNumericField("height: ", (double)first_image_height, 0); // should not be editable ... or at least, explain in some way that the dimensions can be edited just for this image --> done below
						gdr.addMessage("[If dimensions are changed they will apply only to this image]");
						gdr.addMessage("");
						String[] au = new String[]{"fix all", "ignore all"};
						gdr.addChoice("Automate:", au, au[1]);
						gdr.addMessage("Cancel == NO    OK = YES");
						gdr.showDialog();
						if (gdr.wasCanceled()) {
							resize = false;
							// do nothing: don't fix/resize
						}
						resize = true;
						//catch values
						new_width = (int)gdr.getNextNumber();
						new_height = (int)gdr.getNextNumber();
						int iau = gdr.getNextChoiceIndex();
						if (new_width != first_image_width || new_height != first_image_height) {
							auto_fix_all = false;
						} else {
							auto_fix_all = (0 == iau);
						}
						ignore_all = (1 == iau);
						if (ignore_all) resize = false;
					}
					if (resize) {
						//resize Patch dimensions
						rw = first_image_width;
						rh = first_image_height;
					}
				}

				//add new Patch at base bx,by plus the x,y of the grid
				Patch patch = new Patch(layer.getProject(), img.getTitle(), bx + x, by + y, img); // will call back and cache the image
				if (width != rw || height != rh) patch.setDimensions(rw, rh, false);
				//if (null != nlt_coeffs) patch.setNonLinearCoeffs(nlt_coeffs);
				addedPatchFrom(path, patch);
				if (homogenize_contrast) setMipMapsRegeneration(false); // prevent it
				else fus.add(regenerateMipMaps(patch));
				//
				layer.add(patch, true); // after the above two lines! Otherwise it will paint fine, but throw exceptions on the way
				patch.updateInDatabase("tiff_snapshot"); // otherwise when reopening it has to fetch all ImagePlus and scale and zip them all! This method though creates the awt and the snap, thus filling up memory and slowing down, but it's worth it.
				pall[i][j] = patch;

				al.add(patch);
				if (ControlWindow.isGUIEnabled()) {
					layer.getParent().enlargeToFit(patch, LayerSet.NORTHWEST); // northwest to prevent screwing up Patch coordinates.
				}
				y += img.getHeight();
				Utils.showProgress((double)k / n_images);
				k++;
			}
			x += img.getWidth();
			if (largest_y < y) {
				largest_y = y;
			}
			y = 0; //resetting!
		}

		// build list
		final Patch[] pa = new Patch[al.size()];
		int f = 0;
		// list in row-first order
		for (int j=0; j<pall[0].length; j++) { // 'j' is row
			for (int i=0; i<pall.length; i++) { // 'i' is column
				pa[f++] = pall[i][j];
			}
		}
		// optimize repaints: all to background image
		Display.clearSelection(layer);

		// make the first one be top, and the rest under it in left-right and top-bottom order
		for (int j=0; j<pa.length; j++) {
			layer.moveBottom(pa[j]);
		}

		// make picture
		//getFlatImage(layer, layer.getMinimalBoundingBox(Patch.class), 0.25, 1, ImagePlus.GRAY8, Patch.class, null, false).show();

		// optimize repaints: all to background image
		Display.clearSelection(layer);

		if (homogenize_contrast) {
			setTaskName("Enhancing contrast");
			// 0 - check that all images are of the same type
			int tmp_type = pa[0].getType();
			for (int e=1; e<pa.length; e++) {
				if (pa[e].getType() != tmp_type) {
					// can't continue
					tmp_type = Integer.MAX_VALUE;
					Utils.log("Can't homogenize histograms: images are not all of the same type.\nFirst offending image is: " + al.get(e));
					break;
				}
			}
			if (Integer.MAX_VALUE != tmp_type) { // checking on error flag
				// Set min and max for all images
				// 1 - fetch statistics for each image
				final ArrayList al_st = new ArrayList();
				final ArrayList al_p = new ArrayList(); // list of Patch ordered by stdDev ASC
				int type = -1;
				releaseMemory(); // need some to operate
				for (int i=0; i<pa.length; i++) {
					if (this.quit) {
						Display.repaint(layer);
						rollback();
						return;
					}
					ImagePlus imp = fetchImagePlus(pa[i]);
					// speed-up trick: extract data from smaller image
					if (imp.getWidth() > 1024) {
						releaseToFit(1024, (int)((imp.getHeight() * 1024) / imp.getWidth()), imp.getType(), 1.1f);
						// cheap and fast nearest-point resizing
						imp = new ImagePlus(imp.getTitle(), imp.getProcessor().resize(1024));
					}
					if (-1 == type) type = imp.getType();
					ImageStatistics i_st = imp.getStatistics();
					// order by stdDev, from small to big
					int q = 0;
					for (Iterator it = al_st.iterator(); it.hasNext(); ) {
						ImageStatistics st = (ImageStatistics)it.next();
						q++;
						if (st.stdDev > i_st.stdDev) break;
					}
					if (q == al.size()) {
						al_st.add(i_st); // append at the end. WARNING if importing thousands of images, this is a potential source of out of memory errors. I could just recompute it when I needed it again below
						al_p.add(pa[i]);
					} else {
						al_st.add(q, i_st);
						al_p.add(q, pa[i]);
					}
				}
				final ArrayList al_p2 = (ArrayList)al_p.clone(); // shallow copy of the ordered list
				// 2 - discard the first and last 25% (TODO: a proper histogram clustering analysis and histogram examination should apply here)
				if (pa.length > 3) { // under 4 images, use them all
					int i=0;
					while (i <= pa.length * 0.25) {
						al_p.remove(i);
						i++;
					}
					int count = i;
					i = pa.length -1 -count;
					while (i > (pa.length* 0.75) - count) {
						al_p.remove(i);
						i--;
					}
				}
				// 3 - compute common histogram for the middle 50% images
				final Patch[] p50 = new Patch[al_p.size()];
				al_p.toArray(p50);
				StackStatistics stats = new StackStatistics(new PatchStack(p50, 1));
				int n = 1;
				switch (type) {
					case ImagePlus.GRAY16:
					case ImagePlus.GRAY32:
						n = 2;
						break;
				}
				// 4 - compute autoAdjust min and max values
				// extracting code from ij.plugin.frame.ContrastAdjuster, method autoAdjust
				int autoThreshold = 0;
				double min = 0;
				double max = 0;
				// once for 8-bit and color, twice for 16 and 32-bit (thus the 2501 autoThreshold value)
				int limit = stats.pixelCount/10;
				int[] histogram = stats.histogram;
				//if (autoThreshold<10) autoThreshold = 5000;
				//else autoThreshold /= 2;
				if (ImagePlus.GRAY16 == type || ImagePlus.GRAY32 == type) autoThreshold = 2500;
				else autoThreshold = 5000;
				int threshold = stats.pixelCount / autoThreshold;
				int i = -1;
				boolean found = false;
				int count;
				do {
					i++;
					count = histogram[i];
					if (count>limit) count = 0;
					found = count > threshold;
				} while (!found && i<255);
				int hmin = i;
				i = 256;
				do {
					i--;
					count = histogram[i];
					if (count > limit) count = 0;
					found = count > threshold;
				} while (!found && i>0);
				int hmax = i;
				if (hmax >= hmin) {
					min = stats.histMin + hmin*stats.binSize;
					max = stats.histMin + hmax*stats.binSize;
					if (min == max) {
						min = stats.min;
						max = stats.max;
					}
				}
				// 5 - compute common mean within min,max range
				double target_mean = getMeanOfRange(stats, min, max);
				Utils.log2("Loader min,max: " + min + ", " + max + ",   target mean: " + target_mean);
				// 6 - apply to all
				for (i=al_p2.size()-1; i>-1; i--) {
					Patch p = (Patch)al_p2.get(i); // the order is different, thus getting it from the proper list
					double dm = target_mean - getMeanOfRange((ImageStatistics)al_st.get(i), min, max);
					p.setMinAndMax(min - dm, max - dm); // displacing in the opposite direction, makes sense, so that the range is drifted upwards and thus the target 256 range for an awt.Image will be closer to the ideal target_mean
					// OBSOLETE and wrong //p.putMinAndMax(fetchImagePlus(p));
				}

				setMipMapsRegeneration(true);
				if (isMipMapsEnabled()) {
					// recreate files
					for (Patch p : al) fus.add(regenerateMipMaps(p));
				}
				Display.repaint(layer, new Rectangle(0, 0, (int)layer.getParent().getLayerWidth(), (int)layer.getParent().getLayerHeight()), 0);

				// make picture
				//getFlatImage(layer, layer.getMinimalBoundingBox(Patch.class), 0.25, 1, ImagePlus.GRAY8, Patch.class, null, false).show();
			}
		}

		if (stitch_tiles) {
			// Wait until all mipmaps for the new images have been generated before attempting to register
			Utils.wait(fus);

			setTaskName("stitching tiles");
			// create undo
			layer.getParent().addTransformStep(new HashSet<Displayable>(layer.getDisplayables(Patch.class)));
			// wait until repainting operations have finished (otherwise, calling crop on an ImageProcessor fails with out of bounds exception sometimes)
			if (null != Display.getFront()) Display.getFront().getCanvas().waitForRepaint();
			Bureaucrat task = StitchingTEM.stitch(pa, cols.size(), cc_percent_overlap, cc_scale, bt_overlap, lr_overlap, true, stitching_rule);
			if (null != task) try { task.join(); } catch (Exception e) {}
		}

		// link with images on top, bottom, left and right.
		if (link_images) {
			for (int i=0; i<pall.length; i++) { // 'i' is column
				for (int j=0; j<pall[0].length; j++) { // 'j' is row
					Patch p = pall[i][j];
					if (null == p) continue; // can happen if a slot is empty
					if (i>0 && null != pall[i-1][j]) p.link(pall[i-1][j]);
					if (i<pall.length -1 && null != pall[i+1][j]) p.link(pall[i+1][j]);
					if (j>0 && null != pall[i][j-1]) p.link(pall[i][j-1]);
					if (j<pall[0].length -1 && null != pall[i][j+1]) p.link(pall[i][j+1]);
				}
			}
		}

		commitLargeUpdate();

		// resize LayerSet
		int new_width = x;
		int new_height = largest_y;
		layer.getParent().setMinimumDimensions(); //Math.abs(bx) + new_width, Math.abs(by) + new_height);
		// update indexes
		layer.updateInDatabase("stack_index"); // so its done once only
		// create panels in all Displays showing this layer
		/* // not needed anymore
		Iterator it = al.iterator();
		while (it.hasNext()) {
			Display.add(layer, (Displayable)it.next(), false); // don't set it active, don't want to reload the ImagePlus!
		}
		*/
		// update Displays
		Display.update(layer);

		layer.recreateBuckets();

		//debug:
		} catch (Throwable t) {
			IJError.print(t);
			rollback();
			setMipMapsRegeneration(true);
		}
		finishedWorking();

			}// end of run method
		};

		// watcher thread
		return Bureaucrat.createAndStart(worker, layer.getProject());
	}

	public Bureaucrat importImages(final Layer ref_layer) {
		return importImages(ref_layer, null, null, 0, 0, false);
	}
	/** Duplicate layers without creating new mipmaps (davi-experimenting)<br />
	 * purpose: test how TrakEM2 handles 1200 enormous sections instead of 80, in anticipation of a full aligned stack from PSC<br />
	 * code derived from importImages<br />
	 * TODO optionally replicate mipmaps, project objects
	 */
	public Bureaucrat scaleUp(final Layer ref_layer) {
		GenericDialog gdd = new GenericDialog("Scale Up options");
		gdd.addMessage("How many times stack should");
		gdd.addMessage("be replicated (min 1, max 100):");
		gdd.addNumericField("Scale up by: ", 1, 0); // default: 15 x
		gdd.addCheckbox("Generate new mipmaps?", false);
		gdd.showDialog();
		if (gdd.wasCanceled()) return null;
		double scale_up_by_ = gdd.getNextNumber();
		if (scale_up_by_ < 1 || scale_up_by_ > 100 || Double.isNaN(scale_up_by_)) {
			Utils.log("Improper value for 'scale up by'.");
			return null;
		} 
		boolean generate_new_mipmaps_ = gdd.getNextBoolean();
		// make vars accessible from inner threads: (TODO necessary? just blindly following importImages template...)
		final Layer base_layer = ref_layer;
		final Double scale_up_by = scale_up_by_;
		final boolean generate_new_mipmaps = generate_new_mipmaps_;
		
		return Bureaucrat.createAndStart(new Worker.Task("Scaling up", true) {
			public void exec() {
				try {
					final Set<Layer> touched_layers = new HashSet<Layer>(); // TODO why final?
					final LayerSet layer_set = base_layer.getParent();
					ArrayList<Layer> layers = layer_set.getLayers();
					Iterator it = layers.iterator();
					double max_z = Double.MIN_VALUE;
					while (it.hasNext()) {
						Layer cur_layer = (Layer)it.next();
						double cur_z = cur_layer.getZ();		
						// Utils.log2("scaleUp cur_z = " + Double.toString(cur_z));
						if (cur_z > max_z) max_z = cur_z;
					}
					for (int pass = 0; pass < scale_up_by; pass++) {
						it = layers.iterator();
						while (it.hasNext()) {
							max_z = max_z + 1;
							Layer cur_layer = (Layer)it.next();
							// If copy_id (last parameter to cur_layer.clone) is set to false, the new layer and all the patches within it will 
							// get a new id.  This results in new mipmap generation despite the origin path being the same.
							// However, if copy_id is set to true, navigating layers with the scroll wheel is broken. If I were to re-implement
							// clone, such that a new id was created for the layer, but the patches used the old id (thereby avoiding generation of
							// new mipmaps), I suspect that this would also break in strange ways.  But let's test it. ==> works so far
							Layer new_layer = cur_layer.clone(base_layer.getProject(), layer_set, layer_set.get2DBounds(), !generate_new_mipmaps); // layer_set.getLayer(max_z, cur_layer.getThickness(), true); // creates a new layer
							new_layer.setZ(max_z);
							layer_set.add(new_layer);
							base_layer.getProject().getLayerTree().addLayer(layer_set, new_layer);
							touched_layers.add(new_layer);
						}
					}
					// Utils.log2("scaleUp max_z = " + Double.toString(max_z));
					recreateBuckets(touched_layers);
				} catch (Exception e) {
					IJError.print(e);
				}
			}
		}, base_layer.getProject());
	}
	
	/** Import images from the given text file, which is expected to contain 4 columns:<br />
	 * - column 1: image file path (if base_dir is not null, it will be prepended)<br />
	 * - column 2: x coord<br />
	 * - column 3: y coord<br />
	 * - column 4: z coord (layer_thickness will be multiplied to it if not zero)<br />
	 * 
	 * This function implements the "Import from text file" command.
	 *  
	 * Layers will be automatically created as needed inside the LayerSet to which the given ref_layer belongs.. <br />
	 * The text file can contain comments that start with the # sign.<br />
	 * Images will be imported in parallel, using as many cores as your machine has.<br />
	 * The @param calibration transforms the read coordinates into pixel coordinates, including x,y,z, and layer thickness.
	 */
	public Bureaucrat importImages(Layer ref_layer, String abs_text_file_path_, String column_separator_, double layer_thickness_, double calibration_, boolean homogenize_contrast_) {
		// check parameters: ask for good ones if necessary
		if (null == abs_text_file_path_) {
			String[] file = Utils.selectFile("Select text file");
			if (null == file) return null; // user canceled dialog
			abs_text_file_path_ = file[0] + file[1];
		}
		if (null == ref_layer || null == column_separator_ || 0 == column_separator_.length() || Double.isNaN(layer_thickness_) || layer_thickness_ <= 0 || Double.isNaN(calibration_) || calibration_ <= 0) {
			GenericDialog gdd = new GenericDialog("Options");
			String[] separators = new String[]{"tab", "space", "coma (,)"};
			gdd.addMessage("Choose a layer to act as the zero for the Z coordinates:");
			Utils.addLayerChoice("Base layer", ref_layer, gdd);
			gdd.addChoice("Column separator: ", separators, separators[0]);
			gdd.addNumericField("Layer thickness: ", 60, 2); // default: 60 nm
			gdd.addNumericField("Calibration (data to pixels): ", 1, 2);
			gdd.addCheckbox("Homogenize contrast layer-wise", homogenize_contrast_);
			gdd.showDialog();
			if (gdd.wasCanceled()) return null;
			layer_thickness_ = gdd.getNextNumber();
			if (layer_thickness_ < 0 || Double.isNaN(layer_thickness_)) {
				Utils.log("Improper layer thickness value.");
				return null;
			}
			calibration_ = gdd.getNextNumber();
			if (0 == calibration_ || Double.isNaN(calibration_)) {
				Utils.log("Improper calibration value.");
				return null;
			}
			ref_layer = ref_layer.getParent().getLayer(gdd.getNextChoiceIndex());
			column_separator_ = "\t";
			switch (gdd.getNextChoiceIndex()) {
				case 1:
					column_separator_ = " ";
					break;
				case 2:
					column_separator_ = ",";
					break;
				default:
					break;
			}
			homogenize_contrast_ = gdd.getNextBoolean();
		}

		// make vars accessible from inner threads:
		final Layer base_layer = ref_layer;
		final String abs_text_file_path = abs_text_file_path_;
		final String column_separator = column_separator_;
		final double layer_thickness = layer_thickness_;
		final double calibration = calibration_;
		final boolean homogenize_contrast = homogenize_contrast_;


		return Bureaucrat.createAndStart(new Worker.Task("Importing images", true) {
			public void exec() {
				try {
					// 1 - read text file
					final String[] lines = Utils.openTextFileLines(abs_text_file_path);
					if (null == lines || 0 == lines.length) {
						Utils.log2("No images to import from " + abs_text_file_path);
						return;
					}

					ContrastEnhancerWrapper cew = null;
					if (homogenize_contrast) {
						cew = new ContrastEnhancerWrapper();
						cew.showDialog();
					}

					final String sep2 = column_separator + column_separator;
					// 2 - set a base dir path if necessary
					String base_dir = null;

					final Vector<Future> fus = new Vector<Future>(); // to wait on mipmap regeneration

					final LayerSet layer_set = base_layer.getParent();
					final double z_zero = base_layer.getZ();
					final AtomicInteger n_imported = new AtomicInteger(0);
					final Set<Layer> touched_layers = new HashSet<Layer>();

					final int NP = Runtime.getRuntime().availableProcessors();
					int np = NP;
					switch (np) {
						case 1:
						case 2:
							break;
						default:
							np = np / 2;
							break;
					}
					final ExecutorService ex = Executors.newFixedThreadPool(np);
					final List<Future> imported = new ArrayList<Future>();
					final Worker wo = this;

					// 3 - parse each line
					for (int i = 0; i < lines.length; i++) {
						if (Thread.currentThread().isInterrupted() || hasQuitted()) {
							this.quit();
							return;
						}
						// process line
						String line = lines[i].replace('\\','/').trim(); // first thing is the backslash removal, before they get processed at all
						int ic = line.indexOf('#');
						if (-1 != ic) line = line.substring(0, ic); // remove comment at end of line if any
						if (0 == line.length() || '#' == line.charAt(0)) continue;
						// reduce line, so that separators are really unique
						while (-1 != line.indexOf(sep2)) {
							line = line.replaceAll(sep2, column_separator);
						}
						String[] column = line.split(column_separator);
						if (column.length < 4) {
							Utils.log("Less than 4 columns: can't import from line " + i + " : "  + line);
							continue;
						}
						// obtain coordinates
						double x=0,
						       y=0,
						       z=0;
						try {
							x = Double.parseDouble(column[1].trim());
							y = Double.parseDouble(column[2].trim());
							z = Double.parseDouble(column[3].trim());
						} catch (NumberFormatException nfe) {
							Utils.log("Non-numeric value in a numeric column at line " + i + " : " + line);
							continue;
						}
						x *=  calibration;
						y *=  calibration;
						z = z * calibration + z_zero;
						// obtain path
						String path = column[0].trim();
						if (0 == path.length()) continue;
						// check if path is relative
						if ((!IJ.isWindows() && '/' != path.charAt(0)) || (IJ.isWindows() && 1 != path.indexOf(":/"))) {
							//  path is relative.
							if (null == base_dir) { // may not be null if another thread that got the lock first set it to non-null
								//  Ask for source directory
								DirectoryChooser dc = new DirectoryChooser("Choose source directory");
								String dir = dc.getDirectory();
								if (null == dir) {
									// quit all threads
									return;
								}
								base_dir = Utils.fixDir(dir);
							}
						}
						if (null != base_dir) path = base_dir + path;
						File f = new File(path);
						if (!f.exists()) {
							Utils.log("No file found for path " + path);
							continue;
						}

						final Layer layer = layer_set.getLayer(z, layer_thickness, true); // will create a new Layer if necessary
						touched_layers.add(layer);
						final String imagefilepath = path;
						final double xx = x;
						final double yy = y;

						// If loaded twice as many, wait for mipmaps to finish
						// Otherwise, images would end up loaded twice for no reason
						if (0 == (i % (NP+NP))) {
							final ArrayList<Future> a = new ArrayList<Future>(NP+NP);
							synchronized (fus) { // .add is also synchronized, it's a Vector
								int k = 0;
								while (!fus.isEmpty() && k < NP) {
									a.add(fus.remove(0));
									k++;
								}
							}
							for (final Future fu : a) {
								try {
									if (wo.hasQuitted()) return;
									fu.get();
								} catch (Throwable t) {
									t.printStackTrace();
								}
							}
						}

						imported.add(ex.submit(new Runnable() {
							public void run() {
								if (wo.hasQuitted()) return;
								releaseMemory(); //ensures a usable minimum is free
								/* */
								IJ.redirectErrorMessages();
								ImagePlus imp = openImagePlus(imagefilepath);
								if (null == imp) {
									Utils.log("Ignoring unopenable image from " + imagefilepath);
									return;
								}
								// add Patch and generate its mipmaps
								final Patch patch = new Patch(layer.getProject(), imp.getTitle(), xx, yy, imp);
								addedPatchFrom(imagefilepath, patch);
								if (!homogenize_contrast) {
									fus.add(regenerateMipMaps(patch));
								}
								synchronized (layer) {
									layer.add(patch, true);
								}
								wo.setTaskName("Imported " + (n_imported.incrementAndGet() + 1) + "/" + lines.length);
							}
						}));
					}

					Utils.wait(imported);
					ex.shutdown();

					if (0 == n_imported.get()) {
						Utils.log("No images imported.");
						return;
					}

					base_layer.getParent().setMinimumDimensions();
					Display.repaint(base_layer.getParent());

					recreateBuckets(touched_layers);

					if (homogenize_contrast) {
						setTaskName("Enhance contrast");
						// layer-wise (layer order is irrelevant):
						cew.applyLayerWise(touched_layers);
						cew.shutdown();
					}

					Utils.wait(fus);

				} catch (Exception e) {
					IJError.print(e);
				}
			}
		}, base_layer.getProject());
	}

	public Bureaucrat importLabelsAsAreaLists(final Layer layer) {
		return importLabelsAsAreaLists(layer, null, 0, 0, 0.4f, false);
	}

	/** If base_x or base_y are Double.MAX_VALUE, then those values are asked for in a GenericDialog. */
	public Bureaucrat importLabelsAsAreaLists(final Layer first_layer, final String path_, final double base_x_, final double base_y_, final float alpha_, final boolean add_background_) {
		Worker worker = new Worker("Import labels as arealists") {
			public void run() {
				startedWorking();
				try {
					String path = path_;
					if (null == path) {
						OpenDialog od = new OpenDialog("Select stack", "");
						String name = od.getFileName();
						if (null == name || 0 == name.length()) {
							return;
						}
						String dir = od.getDirectory().replace('\\', '/');
						if (!dir.endsWith("/")) dir += "/";
						path = dir + name;
					}
					if (path.toLowerCase().endsWith(".xml")) {
						Utils.log("Avoided opening a TrakEM2 project.");
						return;
					}
					double base_x = base_x_;
					double base_y = base_y_;
					float alpha = alpha_;
					boolean add_background = add_background_;
					Layer layer = first_layer;
					if (Double.MAX_VALUE == base_x || Double.MAX_VALUE == base_y || alpha < 0 || alpha > 1) {
						GenericDialog gd = new GenericDialog("Base x, y");
						Utils.addLayerChoice("First layer:", first_layer, gd);
						gd.addNumericField("Base_X:", 0, 0);
						gd.addNumericField("Base_Y:", 0, 0);
						gd.addSlider("Alpha:", 0, 100, 40);
						gd.addCheckbox("Add background (zero)", false);
						gd.showDialog();
						if (gd.wasCanceled()) {
							return;
						}
						layer = first_layer.getParent().getLayer(gd.getNextChoiceIndex());
						base_x = gd.getNextNumber();
						base_y = gd.getNextNumber();
						if (Double.isNaN(base_x) || Double.isNaN(base_y)) {
							Utils.log("Base x or y is NaN!");
							return;
						}
						alpha = (float)(gd.getNextNumber() / 100);
						add_background = gd.getNextBoolean();
					}
					releaseMemory();
					final ImagePlus imp = openImagePlus(path);
					if (null == imp) {
						Utils.log("Could not open image at " + path);
						return;
					}
					Map<Float,AreaList> alis = AmiraImporter.extractAreaLists(imp, layer, base_x, base_y, alpha, add_background);
					if (!hasQuitted() && alis.size() > 0) {
						layer.getProject().getProjectTree().insertSegmentations(layer.getProject(), alis.values());
					}
				} catch (Exception e) {
					IJError.print(e);
				} finally {
					finishedWorking();
				}
			}
		};
		return Bureaucrat.createAndStart(worker, first_layer.getProject());
	}

	public void recreateBuckets(final Collection<Layer> col) {
		final Layer[] lall = new Layer[col.size()];
		col.toArray(lall);
		recreateBuckets(lall);
	}

	/** Recreate buckets for each Layer, one thread per layer, in as many threads as CPUs. */
	public void recreateBuckets(final Layer[] la) {
		final AtomicInteger ai = new AtomicInteger(0);
		final Thread[] threads = MultiThreading.newThreads();

		for (int ithread = 0; ithread < threads.length; ++ithread) {
			threads[ithread] = new Thread() {
				public void run() {
					setPriority(Thread.NORM_PRIORITY);
					for (int i = ai.getAndIncrement(); i < la.length; i = ai.getAndIncrement()) {
						la[i].recreateBuckets();
					}
				}
			};
		}
		MultiThreading.startAndJoin(threads);
	}

	private double getMeanOfRange(ImageStatistics st, double min, double max) {
		if (min == max) return min;
		double mean = 0;
		int nn = 0;
		int first_bin = 0;
		int last_bin = st.nBins -1;
		for (int b=0; b<st.nBins; b++) {
			if (st.min + st.binSize * b > min) { first_bin = b; break; }
		}
		for (int b=last_bin; b>first_bin; b--) {
			if (st.max - st.binSize * b <= max) { last_bin = b; break; }
		}
		for (int h=first_bin; h<=last_bin; h++) {
			nn += st.histogram[h];
			mean += h * st.histogram[h];
		}
		return mean /= nn;
	}

	/** Used for the revert command. */
	abstract public ImagePlus fetchOriginal(Patch patch);

	public Bureaucrat makeFlatImage(final Layer[] layer, final Rectangle srcRect, final double scale, final int c_alphas, final int type, final boolean force_to_file, final boolean quality) {
		return makeFlatImage(layer, srcRect, scale, c_alphas, type, force_to_file, quality, Color.black);
	}
	/** If the srcRect is null, makes a flat 8-bit or RGB image of the entire layer. Otherwise just of the srcRect. Checks first for enough memory and frees some if feasible. */
	public Bureaucrat makeFlatImage(final Layer[] layer, final Rectangle srcRect, final double scale, final int c_alphas, final int type, final boolean force_to_file, final boolean quality, final Color background) {
		if (null == layer || 0 == layer.length) {
			Utils.log2("makeFlatImage: null or empty list of layers to process.");
			return null;
		}
		final Worker worker = new Worker("making flat images") { public void run() {
			try {
			//
			startedWorking();

			Rectangle srcRect_ = srcRect;
			if (null == srcRect_) srcRect_ = layer[0].getParent().get2DBounds(); 

			ImagePlus imp = null;
			String target_dir = null;
			boolean choose_dir = force_to_file;
			// if not saving to a file:
			if (!force_to_file) {
				final long size = (long)Math.ceil((srcRect_.width * scale) * (srcRect_.height * scale) * ( ImagePlus.GRAY8 == type ? 1 : 4 ) * layer.length);
				if (size > IJ.maxMemory() * 0.9) {
					YesNoCancelDialog yn = new YesNoCancelDialog(IJ.getInstance(), "WARNING", "The resulting stack of flat images is too large to fit in memory.\nChoose a directory to save the slices as an image sequence?");
					if (yn.yesPressed()) {
						choose_dir = true;
					} else if (yn.cancelPressed()) {
						finishedWorking();
						return;
					} else {
						choose_dir = false; // your own risk
					}
				}
			}
			if (choose_dir) {
				final DirectoryChooser dc = new DirectoryChooser("Target directory");
				target_dir = dc.getDirectory();
				if (null == target_dir || target_dir.toLowerCase().startsWith("null")) {
					finishedWorking();
					return;
				}
				if (IJ.isWindows()) target_dir = target_dir.replace('\\', '/');
				if (!target_dir.endsWith("/")) target_dir += "/";
			}
			if (layer.length > 1) {
				// 1 - determine stack voxel depth (by choosing one, if there are layers with different thickness)
				double voxel_depth = 1;
				if (null != target_dir) { // otherwise, saving separately
					ArrayList al_thickness = new ArrayList();
					for (int i=0; i<layer.length; i++) {
						Double t = new Double(layer[i].getThickness());
						if (!al_thickness.contains(t)) al_thickness.add(t);
					}
					if (1 == al_thickness.size()) { // trivial case
						voxel_depth = ((Double)al_thickness.get(0)).doubleValue();
					} else {
						String[] st = new String[al_thickness.size()];
						for (int i=0; i<st.length; i++) {
							st[i] = al_thickness.get(i).toString();
						}
						GenericDialog gdd = new GenericDialog("Choose voxel depth");
						gdd.addChoice("voxel depth: ", st, st[0]);
						gdd.showDialog();
						if (gdd.wasCanceled()) {
							finishedWorking();
							return;
						}
						voxel_depth = ((Double)al_thickness.get(gdd.getNextChoiceIndex())).doubleValue();
					}
				}

				// 2 - get all slices
				ImageStack stack = null;
				for (int i=0; i<layer.length; i++) {
					final ImagePlus slice = getFlatImage(layer[i], srcRect_, scale, c_alphas, type, Displayable.class, null, quality, background);
					if (null == slice) {
						Utils.log("Could not retrieve flat image for " + layer[i].toString());
						continue;
					}
					if (null != target_dir) {
						saveToPath(slice, target_dir, layer[i].getPrintableTitle(), ".tif");
					} else {
						if (null == stack) stack = new ImageStack(slice.getWidth(), slice.getHeight());
						stack.addSlice(layer[i].getProject().findLayerThing(layer[i]).toString(), slice.getProcessor());
					}
				}
				if (null != stack) {
					imp = new ImagePlus("z=" + layer[0].getZ() + " to z=" + layer[layer.length-1].getZ(), stack);
					imp.setCalibration(layer[0].getParent().getCalibrationCopy());
				}
			} else {
				imp = getFlatImage(layer[0], srcRect_, scale, c_alphas, type, Displayable.class, null, quality, background);
				if (null != target_dir) {
					saveToPath(imp, target_dir, layer[0].getPrintableTitle(), ".tif");
					imp = null; // to prevent showing it
				}
			}
			if (null != imp) imp.show();
			} catch (Throwable e) {
				IJError.print(e);
			}
			finishedWorking();
		}}; // I miss my lisp macros, you have no idea
		return Bureaucrat.createAndStart(worker, layer[0].getProject());
	}

	/** Will never overwrite, rather, add an underscore and ordinal to the file name. */
	private void saveToPath(final ImagePlus imp, final String dir, final String file_name, final String extension) {
		if (null == imp) {
			Utils.log2("Loader.saveToPath: can't save a null image.");
			return;
		}
		// create a unique file name
		String path = dir + "/" + file_name;
		File file = new File(path + extension);
		int k = 1;
		while (file.exists()) {
			file = new File(path + "_" + k + ".tif");
			k++;
		}
		try {
			new FileSaver(imp).saveAsTiff(file.getAbsolutePath());
		} catch (OutOfMemoryError oome) {
			Utils.log2("Not enough memory. Could not save image for " + file_name);
			IJError.print(oome);
		} catch (Exception e) {
			Utils.log2("Could not save image for " + file_name);
			IJError.print(e);
		}
	}

	public ImagePlus getFlatImage(final Layer layer, final Rectangle srcRect_, final double scale, final int c_alphas, final int type, final Class clazz, final boolean quality) {
		return getFlatImage(layer, srcRect_, scale, c_alphas, type, clazz, null, quality, Color.black);
	}

	public ImagePlus getFlatImage(final Layer layer, final Rectangle srcRect_, final double scale, final int c_alphas, final int type, final Class clazz, List al_displ) {
		return getFlatImage(layer, srcRect_, scale, c_alphas, type, clazz, al_displ, false, Color.black);
	}

	public ImagePlus getFlatImage(final Layer layer, final Rectangle srcRect_, final double scale, final int c_alphas, final int type, final Class clazz, List al_displ, boolean quality) {
		return getFlatImage(layer, srcRect_, scale, c_alphas, type, clazz, al_displ, quality, Color.black);
	}
	
	public ImagePlus getFlatImage(final Selection selection, final double scale, final int c_alphas, final int type, boolean quality, final Color background) {
		return getFlatImage(selection.getLayer(), selection.getBox(), scale, c_alphas, type, null, selection.getSelected(), quality, background);
	}

	/** Returns a screenshot of the given layer for the given magnification and srcRect. Returns null if the was not enough memory to create it.
	 * @param al_displ The Displayable objects to paint. If null, all those matching Class clazz are included.
	 *
	 * If the 'quality' flag is given, then the flat image is created at a scale of 1.0, and later scaled down using the Image.getScaledInstance method with the SCALE_AREA_AVERAGING flag.
	 *
	 */
	public ImagePlus getFlatImage(final Layer layer, final Rectangle srcRect_, final double scale, final int c_alphas, final int type, final Class clazz, List al_displ, boolean quality, final Color background) {
		final Image bi = getFlatAWTImage(layer, srcRect_, scale, c_alphas, type, clazz, al_displ, quality, background);
		final ImagePlus imp = new ImagePlus(layer.getPrintableTitle(), bi);
		imp.setCalibration(layer.getParent().getCalibrationCopy());
		bi.flush();
		return imp;
	}

	public Image getFlatAWTImage(final Layer layer, final Rectangle srcRect_, final double scale, final int c_alphas, final int type, final Class clazz, List al_displ, boolean quality, final Color background) {

		try {
			// if quality is specified, then a larger image is generated:
			//   - full size if no mipmaps
			//   - double the size if mipmaps is enabled
			double scaleP = scale;
			if (quality) {
				if (isMipMapsEnabled()) {
					// just double the size
					scaleP = scale + scale;
					if (scaleP > 1.0) scaleP = 1.0;
				} else {
					// full
					scaleP = 1.0;
				}
			}

			// dimensions
			int x = 0;
			int y = 0;
			int w = 0;
			int h = 0;
			Rectangle srcRect = (null == srcRect_) ? null : (Rectangle)srcRect_.clone();
			if (null != srcRect) {
				x = srcRect.x;
				y = srcRect.y;
				w = srcRect.width;
				h = srcRect.height;
			} else {
				w = (int)Math.ceil(layer.getLayerWidth());
				h = (int)Math.ceil(layer.getLayerHeight());
				srcRect = new Rectangle(0, 0, w, h);
			}
			Utils.log2("Loader.getFlatImage: using rectangle " + srcRect);
			// estimate image size
			final long n_bytes = (long)((w * h * scaleP * scaleP * (ImagePlus.GRAY8 == type ? 1.0 /*byte*/ : 4.0 /*int*/)));
			Utils.log2("Flat image estimated size in bytes: " + Long.toString(n_bytes) + "  w,h : " + (int)Math.ceil(w * scaleP) + "," + (int)Math.ceil(h * scaleP) + (quality ? " (using 'quality' flag: scaling to " + scale + " is done later with proper area averaging)" : ""));

			if (!releaseToFit(n_bytes)) { // locks on it's own
				Utils.showMessage("Not enough free RAM for a flat image.");
				return null;
			}
			// go
			releaseMemory(); // savage ...

			BufferedImage bi = null;
			switch (type) {
				case ImagePlus.GRAY8:
					bi = new BufferedImage((int)Math.ceil(w * scaleP), (int)Math.ceil(h * scaleP), BufferedImage.TYPE_BYTE_INDEXED, GRAY_LUT);
					break;
				case ImagePlus.COLOR_RGB:
					bi = new BufferedImage((int)Math.ceil(w * scaleP), (int)Math.ceil(h * scaleP), BufferedImage.TYPE_INT_ARGB);
					break;
				default:
					Utils.log2("Left bi,icm as null");
					break;
			}
			final Graphics2D g2d = bi.createGraphics();

			g2d.setColor(background);
			g2d.fillRect(0, 0, bi.getWidth(), bi.getHeight());

			g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON); // to smooth edges of the images
			g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

			releaseMemory(); // savage ...

			ArrayList al_zdispl = null;
			if (null == al_displ) {
				al_displ = layer.getDisplayables(clazz);
				al_zdispl = layer.getParent().getZDisplayables(clazz);
			} else {
				// separate ZDisplayables into their own array
				al_displ = new ArrayList(al_displ);
				//Utils.log2("al_displ size: " + al_displ.size());
				al_zdispl = new ArrayList();
				for (Iterator it = al_displ.iterator(); it.hasNext(); ) {
					Object ob = it.next();
					if (ob instanceof ZDisplayable) {
						it.remove();
						al_zdispl.add(ob);
					}
				}
				// order ZDisplayables by their stack order
				ArrayList al_zdispl2 = layer.getParent().getZDisplayables();
				for (Iterator it = al_zdispl2.iterator(); it.hasNext(); ) {
					Object ob = it.next();
					if (!al_zdispl.contains(ob)) it.remove();
				}
				al_zdispl = al_zdispl2;
			}
	
			// prepare the canvas for the srcRect and magnification
			final AffineTransform at_original = g2d.getTransform();
			final AffineTransform atc = new AffineTransform();
			atc.scale(scaleP, scaleP);
			atc.translate(-srcRect.x, -srcRect.y);
			at_original.preConcatenate(atc);
			g2d.setTransform(at_original);

			//Utils.log2("will paint: " + al_displ.size() + " displ and " + al_zdispl.size() + " zdispl");
			//int total = al_displ.size() + al_zdispl.size();
			int count = 0;
			boolean zd_done = false;
			for(Iterator it = al_displ.iterator(); it.hasNext(); ) {
				Displayable d = (Displayable)it.next();
				//Utils.log2("d is: " + d);
				// paint the ZDisplayables before the first label, if any
				if (!zd_done && d instanceof DLabel) {
					zd_done = true;
					for (Iterator itz = al_zdispl.iterator(); itz.hasNext(); ) {
						ZDisplayable zd = (ZDisplayable)itz.next();
						if (!zd.isOutOfRepaintingClip(scaleP, srcRect, null)) {
							zd.paint(g2d, srcRect, scaleP, false, c_alphas, layer);
						}
						count++;
						//Utils.log2("Painted " + count + " of " + total);
					}
				}
				if (!d.isOutOfRepaintingClip(scaleP, srcRect, null)) {
					d.paintOffscreen(g2d, srcRect, scaleP, false, c_alphas, layer);
					//Utils.log("painted: " + d + "\n with: " + scaleP + ", " + c_alphas + ", " + layer);
				} else {
					//Utils.log2("out: " + d);
				}
				count++;
				//Utils.log2("Painted " + count + " of " + total);
			}
			if (!zd_done) {
				zd_done = true;
				for (Iterator itz = al_zdispl.iterator(); itz.hasNext(); ) {
					ZDisplayable zd = (ZDisplayable)itz.next();
					if (!zd.isOutOfRepaintingClip(scaleP, srcRect, null)) {
						zd.paint(g2d, srcRect, scaleP, false, c_alphas, layer);
					}
					count++;
					//Utils.log2("Painted " + count + " of " + total);
				}
			}
			// ensure enough memory is available for the processor and a new awt from it
			releaseToFit((long)(n_bytes*2.3)); // locks on its own

			try {
				if (quality) {
					// need to scale back down
					Image scaled = null;
					if (!isMipMapsEnabled() || scale >= 0.499) { // there are no proper mipmaps above 50%, so there's need for SCALE_AREA_AVERAGING.
						scaled = bi.getScaledInstance((int)(w * scale), (int)(h * scale), Image.SCALE_AREA_AVERAGING); // very slow, but best by far
						if (ImagePlus.GRAY8 == type) {
							// getScaledInstance generates RGB images for some reason.
							BufferedImage bi8 = new BufferedImage((int)(w * scale), (int)(h * scale), BufferedImage.TYPE_BYTE_GRAY);
							bi8.createGraphics().drawImage(scaled, 0, 0, null);
							scaled.flush();
							scaled = bi8;
						}
					} else {
						// faster, but requires gaussian blurred images (such as the mipmaps)
						if (bi.getType() == BufferedImage.TYPE_BYTE_INDEXED) {
							scaled = new BufferedImage((int)(w * scale), (int)(h * scale), bi.getType(), GRAY_LUT);
						} else {
							scaled = new BufferedImage((int)(w * scale), (int)(h * scale), bi.getType());
						}
						Graphics2D gs = (Graphics2D)scaled.getGraphics();
						//gs.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
						gs.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
						gs.drawImage(bi, 0, 0, (int)(w * scale), (int)(h * scale), null);
					}
					bi.flush();
					return scaled;
				} else {
					// else the image was made scaled down already, and of the proper type
					return bi;
				}
			} catch (OutOfMemoryError oome) {
				Utils.log("Not enough memory to create the ImagePlus. Try scaling it down or not using the 'quality' flag.");
			}

		} catch (Exception e) {
			IJError.print(e);
		}

		return null;
	}

	/** Creates an ImageProcessor of the specified type. */
	public ImageProcessor makeFlatImage(final int type, final Layer layer, final Rectangle srcRect, final double scale, final ArrayList<Patch> patches, final Color background) {
		return Patch.makeFlatImage(type, layer, srcRect, scale, patches, background);
	}


	public Bureaucrat makePrescaledTiles(final Layer[] layer, final Class clazz, final Rectangle srcRect, double max_scale_, final int c_alphas, final int type) {
		return makePrescaledTiles(layer, clazz, srcRect, max_scale_, c_alphas, type, null);
	}

	/** Generate 256x256 tiles, as many as necessary, to cover the given srcRect, starting at max_scale. Designed to be slow but memory-capable.
	 *
	 * filename = z + "/" + row + "_" + column + "_" + s + ".jpg";
	 * 
	 * row and column run from 0 to n stepsize 1
	 * that is, row = y / ( 256 * 2^s ) and column = x / ( 256 * 2^s )
	 *
	 * z : z-level (slice)
	 * x,y: the row and column
	 * s: scale, which is 1 / (2^s), in integers: 0, 1, 2 ...
	 *
	 *  var MAX_S = Math.floor( Math.log( MAX_Y + 1 ) / Math.LN2 ) - Math.floor( Math.log( Y_TILE_SIZE ) / Math.LN2 ) - 1;
	 *
	 * The module should not be more than 5
	 * At al levels, there should be an even number of rows and columns, except for the coarsest level.
	 * The coarsest level should be at least 5x5 tiles.
	 *
	 * Best results obtained when the srcRect approaches or is a square. Black space will pad the right and bottom edges when the srcRect is not exactly a square.
	 * Only the area within the srcRect is ever included, even if actual data exists beyond.
	 *
	 * Returns the watcher thread, for joining purposes, or null if the dialog is canceled or preconditions ar enot passed.
	 */
	public Bureaucrat makePrescaledTiles(final Layer[] layer, final Class clazz, final Rectangle srcRect, double max_scale_, final int c_alphas, final int type, String target_dir) {
		if (null == layer || 0 == layer.length) return null;
		// choose target directory
		if (null == target_dir) {
			DirectoryChooser dc = new DirectoryChooser("Choose target directory");
			target_dir = dc.getDirectory();
			if (null == target_dir) return null;
		}
		if (IJ.isWindows()) target_dir = target_dir.replace('\\', '/');
		if (!target_dir.endsWith("/")) target_dir += "/";

		if (max_scale_ > 1) {
			Utils.log("Prescaled Tiles: using max scale of 1.0");
			max_scale_ = 1; // no point
		}

		final String dir = target_dir;
		final double max_scale = max_scale_;
		final float jpeg_quality = ij.plugin.JpegWriter.getQuality() / 100.0f;
		Utils.log("Using jpeg quality: " + jpeg_quality);

		Worker worker = new Worker("Creating prescaled tiles") {
			private void cleanUp() {
				finishedWorking();
			}
			public void run() {
				startedWorking();

		try {

		// project name
		//String pname = layer[0].getProject().getTitle();

		// create 'z' directories if they don't exist: check and ask!

		// start with the highest scale level
		final int[] best = determineClosestPowerOfTwo(srcRect.width > srcRect.height ? srcRect.width : srcRect.height);
		final int edge_length = best[0];
		final int n_edge_tiles = edge_length / 256;
		Utils.log2("srcRect: " + srcRect);
		Utils.log2("edge_length, n_edge_tiles, best[1] " + best[0] + ", " + n_edge_tiles + ", " + best[1]);


		// thumbnail dimensions
		//LayerSet ls = layer[0].getParent();
		double ratio = srcRect.width / (double)srcRect.height;
		double thumb_scale = 1.0;
		if (ratio >= 1) {
			// width is larger or equal than height
			thumb_scale = 192.0 / srcRect.width;
		} else {
			thumb_scale = 192.0 / srcRect.height;
		}

		for (int iz=0; iz<layer.length; iz++) {
			if (this.quit) {
				cleanUp();
				return;
			}

			// 1 - create a directory 'z' named as the layer's Z coordinate
			String tile_dir = dir + layer[iz].getParent().indexOf(layer[iz]);
			File fdir = new File(tile_dir);
			int tag = 1;
			// Ensure there is a usable directory:
			while (fdir.exists() && !fdir.isDirectory()) {
				fdir = new File(tile_dir + "_" + tag);
			}
			if (!fdir.exists()) {
				fdir.mkdir();
				Utils.log("Created directory " + fdir);
			}
			// if the directory exists already just reuse it, overwritting its files if so.
			final String tmp = fdir.getAbsolutePath().replace('\\','/');
			if (!tile_dir.equals(tmp)) Utils.log("\tWARNING: directory will not be in the standard location.");
			// debug:
			Utils.log2("tile_dir: " + tile_dir + "\ntmp: " + tmp);
			tile_dir = tmp;
			if (!tile_dir.endsWith("/")) tile_dir += "/";

			// 2 - create layer thumbnail, max 192x192
			ImagePlus thumb = getFlatImage(layer[iz], srcRect, thumb_scale, c_alphas, type, clazz, true);
			ImageSaver.saveAsJpeg(thumb.getProcessor(), tile_dir + "small.jpg", jpeg_quality, ImagePlus.COLOR_RGB != type);
			flush(thumb);
			thumb = null;

			// 3 - fill directory with tiles
			if (edge_length < 256) { // edge_length is the largest length of the 256x256 tile map that covers an area equal or larger than the desired srcRect (because all tiles have to be 256x256 in size)
				// create single tile per layer
				makeTile(layer[iz], srcRect, max_scale, c_alphas, type, clazz, jpeg_quality, tile_dir + "0_0_0.jpg");
			} else {
				// create piramid of tiles
				double scale = 1; //max_scale; // WARNING if scale is different than 1, it will FAIL to set the next scale properly.
				int scale_pow = 0;
				int n_et = n_edge_tiles; // cached for local modifications in the loop, works as loop controler
				while (n_et >= best[1]) { // best[1] is the minimal root found, i.e. 1,2,3,4,5 from hich then powers of two were taken to make up for the edge_length
					int tile_side = (int)(256/scale); // 0 < scale <= 1, so no precision lost
					for (int row=0; row<n_et; row++) {
						for (int col=0; col<n_et; col++) {
							final int i_tile = row * n_et + col;
							Utils.showProgress(i_tile /  (double)(n_et * n_et));

							if (0 == i_tile % 100) {
								releaseMemory();
							}

							if (this.quit) {
								cleanUp();
								return;
							}
							Rectangle tile_src = new Rectangle(srcRect.x + tile_side*row,
									                   srcRect.y + tile_side*col,
											   tile_side,
											   tile_side); // in absolute coords, magnification later.
							// crop bounds
							if (tile_src.x + tile_src.width > srcRect.x + srcRect.width) tile_src.width = srcRect.x + srcRect.width - tile_src.x;
							if (tile_src.y + tile_src.height > srcRect.y + srcRect.height) tile_src.height = srcRect.y + srcRect.height - tile_src.y;
							// negative tile sizes will be made into black tiles
							// (negative dimensions occur for tiles beyond the edges of srcRect, since the grid of tiles has to be of equal number of rows and cols)
							makeTile(layer[iz], tile_src, scale, c_alphas, type, clazz, jpeg_quality, new StringBuffer(tile_dir).append(col).append('_').append(row).append('_').append(scale_pow).append(".jpg").toString()); // should be row_col_scale, but results in transposed tiles in googlebrains, so I inversed it.
						}
					}
					scale_pow++;
					scale = 1 / Math.pow(2, scale_pow); // works as magnification
					n_et /= 2;
				}
			}
		}
		} catch (Exception e) {
			IJError.print(e);
		} finally {
			Utils.showProgress(1);
		}
		cleanUp();
		finishedWorking();

			}// end of run method
		};

		// watcher thread
		return Bureaucrat.createAndStart(worker, layer[0].getProject());
	}

	/** Will overwrite if the file path exists. */
	private void makeTile(Layer layer, Rectangle srcRect, double mag, int c_alphas, int type, Class clazz, final float jpeg_quality, String file_path) throws Exception {
		ImagePlus imp = null;
		if (srcRect.width > 0 && srcRect.height > 0) {
			imp = getFlatImage(layer, srcRect, mag, c_alphas, type, clazz, null, true); // with quality
		} else {
			imp = new ImagePlus("", new ByteProcessor(256, 256)); // black tile
		}
		// correct cropped tiles
		if (imp.getWidth() < 256 || imp.getHeight() < 256) {
			ImagePlus imp2 = new ImagePlus(imp.getTitle(), imp.getProcessor().createProcessor(256, 256));
			// ensure black background for color images
			if (imp2.getType() == ImagePlus.COLOR_RGB) {
				Roi roi = new Roi(0, 0, 256, 256);
				imp2.setRoi(roi);
				imp2.getProcessor().setValue(0); // black
				imp2.getProcessor().fill();
			}
			imp2.getProcessor().insert(imp.getProcessor(), 0, 0);
			imp = imp2;
		}
		// debug
		//Utils.log("would save: " + srcRect + " at " + file_path);
		ImageSaver.saveAsJpeg(imp.getProcessor(), file_path, jpeg_quality, ImagePlus.COLOR_RGB != type);
	}

	/** Find the closest, but larger, power of 2 number for the given edge size; the base root may be any of {1,2,3,5}. */
	private int[] determineClosestPowerOfTwo(final int edge) {
		int[] starter = new int[]{1, 2, 3, 5}; // I love primer numbers
		int[] larger = new int[starter.length]; System.arraycopy(starter, 0, larger, 0, starter.length); // I hate java's obscene verbosity
		for (int i=0; i<larger.length; i++) {
			while (larger[i] < edge) {
				larger[i] *= 2;
			}
		}
		int min_larger = larger[0];
		int min_i = 0;
		for (int i=1; i<larger.length; i++) {
			if (larger[i] < min_larger) {
				min_i = i;
				min_larger = larger[i];
			}
		}
		// 'larger' is now larger or equal to 'edge', and will reduce to starter[min_i] tiles squared.
		return new int[]{min_larger, starter[min_i]};
	}

	/** WARNING may be altered concurrently. */
	private String last_opened_path = null;

	/** Subclasses can override this method to register the URL of the imported image. */
	public void addedPatchFrom(String path, Patch patch) {}

	/** Import an image into the given layer, in a separate task thread. */
	public Bureaucrat importImage(final Layer layer, final double x, final double y, final String path, final boolean synch_mipmap_generation) {
		Worker worker = new Worker("Importing image") {
			public void run() {
				startedWorking();
				try {
					////
					if (null == layer) {
						Utils.log("Can't import. No layer found.");
						finishedWorking();
						return;
					}
					Patch p = importImage(layer.getProject(), x, y, path, synch_mipmap_generation);
					if (null != p) {
						synchronized (layer) {
							layer.add(p);
						}
						layer.getParent().enlargeToFit(p, LayerSet.NORTHWEST);
					}
					////
				} catch (Exception e) {
					IJError.print(e);
				}
				finishedWorking();
			}
		};
		return Bureaucrat.createAndStart(worker, layer.getProject());
	}

	public Patch importImage(Project project, double x, double y) {
		return importImage(project, x, y, null, false);
	}
	/** Import a new image at the given coordinates; does not puts it into any layer, unless it's a stack -in which case importStack is called with the current front layer of the given project as target. If a path is not provided it will be asked for.*/
	public Patch importImage(Project project, double x, double y, String path, boolean synch_mipmap_generation) {
		if (null == path) {
			OpenDialog od = new OpenDialog("Import image", "");
			String name = od.getFileName();
			if (null == name || 0 == name.length()) return null;
			String dir = od.getDirectory().replace('\\', '/');
			if (!dir.endsWith("/")) dir += "/";
			path = dir + name;
		} else {
			path = path.replace('\\', '/');
		}
		// avoid opening trakem2 projects
		if (path.toLowerCase().endsWith(".xml")) {
			Utils.showMessage("Cannot import " + path + " as a stack.");
			return null;
		}
		releaseMemory(); // some: TODO this should read the header only, and figure out the dimensions to do a releaseToFit(n_bytes) call
		IJ.redirectErrorMessages();
		final ImagePlus imp = openImagePlus(path);
		if (null == imp) return null;
		if (imp.getNSlices() > 1) {
			// a stack!
			Layer layer = Display.getFrontLayer(project);
			if (null == layer) return null;
			importStack(layer, x, y, imp, true, path, true);
			return null;
		}
		if (0 == imp.getWidth() || 0 == imp.getHeight()) {
			Utils.showMessage("Can't import image of zero width or height.");
			flush(imp);
			return null;
		}
		Patch p = new Patch(project, imp.getTitle(), x, y, imp);
		addedPatchFrom(path, p);
		last_opened_path = path; // WARNING may be altered concurrently
		if (isMipMapsEnabled()) {
			if (synch_mipmap_generation) generateMipMaps(p);
			else regenerateMipMaps(p); // queue for regeneration
		}
		return p;
	}
	public Patch importNextImage(Project project, double x, double y) {
		if (null == last_opened_path) {
			return importImage(project, x, y);
		}
		int i_slash = last_opened_path.lastIndexOf("/");
		String dir_name = last_opened_path.substring(0, i_slash + 1);
		File dir = new File(dir_name);
		String last_file = last_opened_path.substring(i_slash + 1);
		String[] file_names = dir.list();
		String next_file = null;
		final String exts = "tiftiffjpgjpegpnggifzipdicombmppgm";
		for (int i=0; i<file_names.length; i++) {
			if (last_file.equals(file_names[i]) && i < file_names.length -1) {
				// loop until finding a suitable next
				for (int j=i+1; j<file_names.length; j++) {
					String ext = file_names[j].substring(file_names[j].lastIndexOf('.') + 1).toLowerCase();
					if (-1 != exts.indexOf(ext)) {
						next_file = file_names[j];
						break;
					}
				}
				break;
			}
		}
		if (null == next_file) {
			Utils.showMessage("No more files after " + last_file);
			return null;
		}
		releaseMemory(); // some: TODO this should read the header only, and figure out the dimensions to do a releaseToFit(n_bytes) call
		IJ.redirectErrorMessages();
		ImagePlus imp = openImagePlus(dir_name + next_file);
		if (null == imp) return null;
		if (0 == imp.getWidth() || 0 == imp.getHeight()) {
			Utils.showMessage("Can't import image of zero width or height.");
			flush(imp);
			return null;
		}
		String path = dir + "/" + next_file;
		Patch p = new Patch(project, imp.getTitle(), x, y, imp);
		addedPatchFrom(path, p);
		last_opened_path = path; // WARNING may be altered concurrently
		if (isMipMapsEnabled()) regenerateMipMaps(p);
		return p;
	}

	public Bureaucrat importStack(Layer first_layer, ImagePlus imp_stack_, boolean ask_for_data) {
		return importStack(first_layer, imp_stack_, ask_for_data, null);
	}
	public Bureaucrat importStack(final Layer first_layer, final ImagePlus imp_stack_, final boolean ask_for_data, final String filepath_) {
		return importStack(first_layer, 0, 0, imp_stack_, ask_for_data, filepath_, true);
	}
	/** Imports an image stack from a multitiff file and places each slice in the proper layer, creating new layers as it goes. If the given stack is null, popup a file dialog to choose one*/
	public Bureaucrat importStack(final Layer first_layer, final double x, final double y, final ImagePlus imp_stack_, final boolean ask_for_data, final String filepath_, final boolean one_patch_per_layer_) {
		Utils.log2("Loader.importStack filepath: " + filepath_);
		if (null == first_layer) return null;

		Worker worker = new Worker("import stack") {
			public void run() {
				startedWorking();
				try {


		String filepath = filepath_;
		boolean one_patch_per_layer = one_patch_per_layer_;
		/* On drag and drop the stack is not null! */ //Utils.log2("imp_stack_ is " + imp_stack_);
		ImagePlus[] stks = null;
		boolean choose = false;
		if (null == imp_stack_) {
			stks = Utils.findOpenStacks();
			choose = null == stks || stks.length > 0;
		} else {
			stks = new ImagePlus[]{imp_stack_};
		}
		ImagePlus imp_stack = null;
		// ask to open a stack if it's null
		if (null == stks) {
			imp_stack = openStack(); // choose one
		} else if (choose) {
			// choose one from the list
			GenericDialog gd = new GenericDialog("Choose one");
			gd.addMessage("Choose a stack from the list or 'open...' to bring up a file chooser dialog:");
			String[] list = new String[stks.length +1];
			for (int i=0; i<list.length -1; i++) {
				list[i] = stks[i].getTitle();
			}
			list[list.length-1] = "[  Open stack...  ]";
			gd.addChoice("choose stack: ", list, list[0]);
			gd.showDialog();
			if (gd.wasCanceled()) {
				finishedWorking();
				return;
			}
			int i_choice = gd.getNextChoiceIndex();
			if (list.length-1 == i_choice) { // the open... command
				imp_stack = first_layer.getProject().getLoader().openStack();
			} else {
				imp_stack = stks[i_choice];
			}
		} else {
			imp_stack = imp_stack_;
		}
		// check:
		if (null == imp_stack) {
			finishedWorking();
			return;
		}

		final String props = (String)imp_stack.getProperty("Info");

		// check if it's amira labels stack to prevent missimports
		if (null != props && -1 != props.indexOf("Materials {")) {
			YesNoDialog yn = new YesNoDialog(IJ.getInstance(), "Warning", "You are importing a stack of Amira labels as a regular image stack. Continue anyway?");
			if (!yn.yesPressed()) {
				finishedWorking();
				return;
			}
		}

		//String dir = imp_stack.getFileInfo().directory;
		double layer_width = first_layer.getLayerWidth();
		double layer_height= first_layer.getLayerHeight();
		double current_thickness = first_layer.getThickness();
		double thickness = current_thickness;
		boolean expand_layer_set = false;
		boolean lock_stack = false;
		//int anchor = LayerSet.NORTHWEST; //default
		if (ask_for_data) {
			// ask for slice separation in pixels
			GenericDialog gd = new GenericDialog("Slice separation?");
			gd.addMessage("Please enter the slice thickness, in pixels");
			gd.addNumericField("slice_thickness: ", Math.abs(imp_stack.getCalibration().pixelDepth / imp_stack.getCalibration().pixelHeight), 3); // assuming pixelWidth == pixelHeight
			if (layer_width != imp_stack.getWidth() || layer_height != imp_stack.getHeight()) {
				gd.addCheckbox("Resize canvas to fit stack", true);
				//gd.addChoice("Anchor: ", LayerSet.ANCHORS, LayerSet.ANCHORS[0]);
			}
			gd.addCheckbox("Lock stack", false);
			final String[] importStackTypes = {"One slice per layer (Patches)", "Image volume (Stack)"};
			if (imp_stack.getStack().isVirtual()) {
				one_patch_per_layer = true;
			}
			gd.addChoice("Import stack as:", importStackTypes, importStackTypes[0]);
			((Component)gd.getChoices().get(0)).setEnabled(!imp_stack.getStack().isVirtual());
			gd.showDialog();
			if (gd.wasCanceled()) {
				if (null == stks) { // flush only if it was not open before
					flush(imp_stack);
				}
				finishedWorking();
				return;
			}
			if (layer_width != imp_stack.getWidth() || layer_height != imp_stack.getHeight()) {
				expand_layer_set = gd.getNextBoolean();
				//anchor = gd.getNextChoiceIndex();
			}
			lock_stack = gd.getNextBoolean();
			thickness = gd.getNextNumber();
			// check provided thickness with that of the first layer:
			if (thickness != current_thickness) {
				if (1 == first_layer.getParent().size() && first_layer.isEmpty()) {
					YesNoCancelDialog yn = new YesNoCancelDialog(IJ.getInstance(), "Mismatch!", "The current layer's thickness is " + current_thickness + "\nwhich is " + (thickness < current_thickness ? "larger":"smaller") + " than\nthe desired " + thickness + " for each stack slice.\nAdjust current layer's thickness to " + thickness + " ?");
					if (yn.cancelPressed()) {
						if (null != imp_stack_) flush(imp_stack); // was opened new
						finishedWorking();
						return;
					} else if (yn.yesPressed()) {
						first_layer.setThickness(thickness);
						// The rest of layers, created new, will inherit the same thickness
					}
				} else {
					YesNoDialog yn = new YesNoDialog(IJ.getInstance(), "WARNING", "There's more than one layer or the current layer is not empty\nso the thickness cannot be adjusted. Proceed anyway?");
					if (!yn.yesPressed()) {
						finishedWorking();
						return;
					}
				}
			}

			one_patch_per_layer = imp_stack.getStack().isVirtual() || 0 == gd.getNextChoiceIndex();
		}

		if (null == imp_stack.getStack()) {
			Utils.showMessage("Not a stack.");
			finishedWorking();
			return;
		}

		// WARNING: there are fundamental issues with calibration, because the Layer thickness is disconnected from the Calibration pixelDepth

		// set LayerSet calibration if there is no calibration
		boolean calibrate = true;
		if (ask_for_data && first_layer.getParent().isCalibrated()) {
			if (!ControlWindow.isGUIEnabled()) {
				Utils.log2("Loader.importStack: overriding LayerSet calibration with that of the imported stack.");
			} else {
				YesNoDialog yn = new YesNoDialog("Calibration", "The layer set is already calibrated. Override with the stack calibration values?");
				if (!yn.yesPressed()) {
					calibrate = false;
				}
			}
		}
		if (calibrate) {
			first_layer.getParent().setCalibration(imp_stack.getCalibration());
		}

		if (layer_width < imp_stack.getWidth() || layer_height < imp_stack.getHeight()) {
			expand_layer_set = true;
		}

		if (imp_stack.getStack().isVirtual()) {
			// do nothing
		} else if (null == filepath) {
			// try to get it from the original FileInfo
			final FileInfo fi = imp_stack.getOriginalFileInfo();
			if (null != fi && null != fi.directory && null != fi.fileName) {
				filepath = fi.directory.replace('\\', '/');
				if (!filepath.endsWith("/")) filepath += '/';
				filepath += fi.fileName;
			}
			Utils.log2("Getting filepath from FileInfo: " + filepath);
			// check that file exists, otherwise save a copy in the storage folder
			if (null == filepath || (!filepath.startsWith("http://") && !new File(filepath).exists())) {
				filepath = handlePathlessImage(imp_stack);
			}
		} else {
			filepath = filepath.replace('\\', '/');
		}


		// Import as Stack ZDisplayable object:
		if (!one_patch_per_layer) {
			Stack st = new Stack(first_layer.getProject(), new File(filepath).getName(), x, y, first_layer, filepath);
			first_layer.getParent().add(st);
			finishedWorking();
			return;
		}

		// Place the first slice in the current layer, and then query the parent LayerSet for subsequent layers, and create them if not present.
		Patch last_patch = Loader.this.importStackAsPatches(first_layer.getProject(), first_layer, x, y, imp_stack, null != imp_stack_ && null != imp_stack_.getCanvas(), filepath);
		if (null != last_patch) {
			last_patch.setLocked(lock_stack);
			Display.updateCheckboxes(last_patch.getLinkedGroup(null), DisplayablePanel.LOCK_STATE, true);
		}

		if (expand_layer_set) {
			last_patch.getLayer().getParent().setMinimumDimensions();
		}

		Utils.log2("props: " + props);

		// check if it's an amira stack, then ask to import labels
		if (null != props && -1 == props.indexOf("Materials {") && -1 != props.indexOf("CoordType")) {
			YesNoDialog yn = new YesNoDialog(IJ.getInstance(), "Amira Importer", "Import labels as well?");
			if (yn.yesPressed()) {
				// select labels
				Collection<AreaList> alis = AmiraImporter.importAmiraLabels(first_layer, last_patch.getX(), last_patch.getY(), imp_stack.getOriginalFileInfo().directory);
				if (null != alis) {
					// import all created AreaList as nodes in the ProjectTree under a new imported_segmentations node
					first_layer.getProject().getProjectTree().insertSegmentations(first_layer.getProject(), alis);
					// link them to the images
					for (final AreaList ali : alis) {
						ali.linkPatches();
					}
				}
			}
		}

		// it is safe not to flush the imp_stack, because all its resources are being used anyway (all the ImageProcessor), and it has no awt.Image. Unless it's being shown in ImageJ, and then it will be flushed on its own when the user closes its window.
				} catch (Exception e) {
					IJError.print(e);
				}
				finishedWorking();
			}
		};
		return Bureaucrat.createAndStart(worker, first_layer.getProject());
	}

	public String handlePathlessImage(ImagePlus imp) { return null; }

	protected Patch importStackAsPatches(final Project project, final Layer first_layer, final ImagePlus stack, final boolean as_copy, String filepath) {
		return importStackAsPatches(project, first_layer, Double.MAX_VALUE, Double.MAX_VALUE, stack, as_copy, filepath);
	}
	abstract protected Patch importStackAsPatches(final Project project, final Layer first_layer, final double x, final double y, final ImagePlus stack, final boolean as_copy, String filepath);

	protected String export(Project project, File fxml) {
		return export(project, fxml, true);
	}

	/** Exports the project and its images (optional); if export_images is true, it will be asked for confirmation anyway -beware: for FSLoader, images are not exported since it doesn't own them; only their path.*/
	protected String export(final Project project, final File fxml, boolean export_images) {
		releaseToFit(MIN_FREE_BYTES);
		String path = null;
		if (null == project || null == fxml) return null;
		try {
			if (export_images && !(this instanceof FSLoader))  {
				final YesNoCancelDialog yn = ini.trakem2.ControlWindow.makeYesNoCancelDialog("Export images?", "Export images as well?");
				if (yn.cancelPressed()) return null;
				if (yn.yesPressed()) export_images = true;
				else export_images = false; // 'no' option
			}
			// 1 - get headers in DTD format
			StringBuffer sb_header = new StringBuffer("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<!DOCTYPE ").append(project.getDocType()).append(" [\n");

			final HashSet hs = new HashSet();
			project.exportDTD(sb_header, hs, "\t");

			sb_header.append("] >\n\n");

			//  2 - fill in the data
			String patches_dir = null;
			if (export_images) {
				patches_dir = makePatchesDir(fxml);
			}
			java.io.Writer writer = new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(fxml)), "8859_1");
			try {
				writer.write(sb_header.toString());
				sb_header = null;
				project.exportXML(writer, "", patches_dir);
				writer.flush(); // make sure all buffered chars are written
				setChanged(false);
				path = fxml.getAbsolutePath().replace('\\', '/');
			} catch (Exception e) {
				Utils.log("FAILED to save the file at " + fxml);
				IJError.print(e);
				path = null;
			} finally {
				writer.close();
				writer = null;
			}

			// Remove the patches_dir if empty (can happen when doing a "save" on a FSLoader project if no new Patch have been created that have no path.
			if (export_images) {
				File fpd = new File(patches_dir);
				if (fpd.exists() && fpd.isDirectory()) {
					// check if it contains any files
					File[] ff = fpd.listFiles();
					boolean rm = true;
					for (int k=0; k<ff.length; k++) {
						if (!ff[k].isHidden()) {
							// one non-hidden file found.
							rm = false;
							break;
						}
					}
					if (rm) {
						try {
							fpd.delete();
						} catch (Exception e) {
							Utils.log2("Could not delete empty directory " + patches_dir);
							IJError.print(e);
						}
					}
				}
			}

		} catch (Exception e) {
			IJError.print(e);
		}
		ControlWindow.updateTitle(project);
		return path;
	}

	static public long countObjects(final LayerSet ls) {
		// estimate total number of bytes: large estimate is 500 bytes of xml text for each object
		int count = 1; // the given LayerSet itself
		for (Layer la : (ArrayList<Layer>)ls.getLayers()) {
			count += la.getNDisplayables();
			for (Object ls2 : la.getDisplayables(LayerSet.class)) { // can't cast ArrayList<Displayable> to ArrayList<LayerSet> ????
				count += countObjects((LayerSet)ls2);
			}
		}
		return count;
	}

	/** Calls saveAs() unless overriden. Returns full path to the xml file. */
	public String save(Project project) { // yes the project is the same project pointer, which for some reason I never committed myself to place it in the Loader class as a field.
		String path = saveAs(project);
		if (null != path) setChanged(false);
		return path;
	}

	/** Save the project under a different name by choosing from a dialog, and exporting all images (will popup a YesNoCancelDialog to confirm exporting images.) */
	public String saveAs(Project project) {
		return saveAs(project, null, true);
	}

	/** Exports to an XML file chosen by the user in a dialog if @param xmlpath is null. Images exist already in the file system, so none are exported. Returns the full path to the xml file. */
	public String saveAs(Project project, String xmlpath, boolean export_images) {
		long size = countObjects(project.getRootLayerSet()) * 500;
		releaseToFit(size > MIN_FREE_BYTES ? size : MIN_FREE_BYTES);
		String storage_dir = getStorageFolder();
		String mipmaps_dir = getMipMapsFolder();
		// Select a file to export to
		File fxml = null == xmlpath ? Utils.chooseFile(storage_dir, null, ".xml") : new File(xmlpath);
		Hashtable<Long,String> copy = null;
		if (null == fxml) return null;
		else {
			copy = getPathsCopy();
			makeAllPathsRelativeTo(fxml.getAbsolutePath().replace('\\', '/'));
		}
		String path = export(project, fxml, export_images);
		if (null != path) setChanged(false);
		else {
			// failed, so restore paths
			restorePaths(copy, mipmaps_dir, storage_dir);
		}
		//
		return path;
	}

	protected void makeAllPathsRelativeTo(final String xml_path) {}
	protected Hashtable<Long,String> getPathsCopy() { return null; }
	protected void restorePaths(final Hashtable<Long,String> copy, final String mipmaps_folder, final String storage_folder) {}

	/** Meant to be overriden -- as is, will call saveAs(project, path, export_images = getClass() != FSLoader.class ). */
	public String saveAs(String path, boolean overwrite) {
		if (null == path) return null;
		return export(Project.findProject(this), new File(path), this.getClass() != FSLoader.class);
	}

	/** Parses the xml_path and returns the folder in the same directory that has the same name plus "_images". Note there isn't an ending backslash. */
	private String extractRelativeFolderPath(final File fxml) {
		try {
			String patches_dir = Utils.fixDir(fxml.getParent()) + fxml.getName();
			if (patches_dir.toLowerCase().lastIndexOf(".xml") == patches_dir.length() - 4) {
				patches_dir = patches_dir.substring(0, patches_dir.lastIndexOf('.'));
			}
			return patches_dir + "_images"; // NOTE: no ending backslash
		} catch (Exception e) {
			IJError.print(e);
			return null;
		}
	}

	protected String makePatchesDir(final File fxml) {
		// Create a directory to store the images
		String patches_dir = extractRelativeFolderPath(fxml); // WITHOUT ending backslash
		if (null == patches_dir) return null;
		File dir = new File(patches_dir);
		String patches_dir2 = null;
		int i = 1;
		while (dir.exists()) {
			patches_dir2 = patches_dir + "_" + Integer.toString(i);
			dir = new File(patches_dir2);
			i++;
		}
		if (null != patches_dir2) patches_dir = patches_dir2;
		if (null == patches_dir) return null;
		try {
			dir.mkdir();
		} catch (Exception e) {
			IJError.print(e);
			Utils.showMessage("Could not create a directory for the images.");
			return null;
		}
		return Utils.fixDir(patches_dir);
	}

	public String exportImage(final Patch patch, final String path, final boolean overwrite) {
		return exportImage(patch, fetchImagePlus(patch), path, overwrite);
	}

	/** Returns the path to the saved image, or null if not saved. */
	public String exportImage(final Patch patch, final ImagePlus imp, final String path, final boolean overwrite) {
		// if !overwrite, save only if not there already
		if (null == path || null == imp || (!overwrite && new File(path).exists())) return null;
		try {
			if (imp.getNSlices() > 1) new FileSaver(imp).saveAsTiffStack(path);
			else new FileSaver(imp).saveAsTiff(path);
		} catch (Exception e) {
			Utils.log("Could not save an image for Patch #" + patch.getId() + " at: " + path);
			IJError.print(e);
			return null;
		}
		return path;
	}

	/** Whether any changes need to be saved. */
	public boolean hasChanges() {
		return this.changes;
	}

	public void setChanged(final boolean changed) {
		this.changes = changed;
		//Utils.printCaller(this, 7);
	}

	/** Returns null unless overriden. This is intended for FSLoader projects. */
	public String getPath(final Patch patch) { return null; }

	/** Returns null unless overriden. This is intended for FSLoader projects. */
	public String getAbsolutePath(final Patch patch) { return null; }

	/** Returns null unless overriden. This is intended for FSLoader projects. */
	public String getImageFilePath(final Patch p) { return null; }

	/** Does nothing unless overriden. */
	public void setupMenuItems(final JMenu menu, final Project project) {}

	/** Test whether this Loader needs recurrent calls to a "save" of some sort, such as for the FSLoader. */
	public boolean isAsynchronous() {
		// in the future, DBLoader may also be asynchronous
		return this.getClass() == FSLoader.class;
	}

	/** Throw away all awts and snaps that depend on this image, so that they will be recreated next time they are needed. */
	public void decache(final ImagePlus imp) {
		synchronized(db_lock) {
			lock();
			try {
				final long id = imps.getId(imp);
				Utils.log2("decaching " + id);
				if (Long.MIN_VALUE == id) return;
				mawts.removeAndFlush(id);
			} catch (Exception e) {
				IJError.print(e);
			} finally {
				unlock();
			}
		}
	}

	protected final void preProcess(final Patch p, ImagePlus imp) {
		if (null == p) return;
		try {
			String path = preprocessors.get(p);
			if (null == path) return;
			if (null != imp) {
				// Prepare image for pre-processing
				imp.getProcessor().setMinAndMax(p.getMin(), p.getMax()); // for 8-bit and RGB images, your problem: setting min and max will expand the range.
			} else {
				imp = new ImagePlus(); // uninitialized: the script may generate its data
			}
			// Run the script
			ini.trakem2.scripting.PatchScript.run(p, imp, path);
			// Update Patch image properties:
			cache(p, imp);
			p.updatePixelProperties();
		} catch (Exception e) {
			IJError.print(e);
		}
	}

	///////////////////////


	/** List of jobs running on this Loader. */
	private ArrayList al_jobs = new ArrayList();
	private JPopupMenu popup_jobs = null;
	private final Object popup_lock = new Object();
	private boolean popup_locked = false;

	/** Adds a new job to monitor.*/
	public void addJob(Bureaucrat burro) {
		synchronized (popup_lock) {
			while (popup_locked) try { popup_lock.wait(); } catch (InterruptedException ie) {}
			popup_locked = true;
			al_jobs.add(burro);
			popup_locked = false;
			popup_lock.notifyAll();
		}
	}
	public void removeJob(Bureaucrat burro) {
		synchronized (popup_lock) {
			while (popup_locked) try { popup_lock.wait(); } catch (InterruptedException ie) {}
			popup_locked = true;
			if (null != popup_jobs && popup_jobs.isVisible()) {
				popup_jobs.setVisible(false);
			}
			al_jobs.remove(burro);
			popup_locked = false;
			popup_lock.notifyAll();
		}
	}
	public JPopupMenu getJobsPopup(Display display) {
		synchronized (popup_lock) {
			while (popup_locked) try { popup_lock.wait(); } catch (InterruptedException ie) {}
			popup_locked = true;
			this.popup_jobs = new JPopupMenu("Cancel jobs:");
			int i = 1;
			for (Iterator it = al_jobs.iterator(); it.hasNext(); ) {
				Bureaucrat burro = (Bureaucrat)it.next();
				JMenuItem item = new JMenuItem("Job " + i + ": " + burro.getTaskName());
				item.addActionListener(display);
				popup_jobs.add(item);
				i++;
			}
			popup_locked = false;
			popup_lock.notifyAll();
		}
		return popup_jobs;
	}
	/** Names as generated for popup menu items in the getJobsPopup method. If the name is null, it will cancel the last one. Runs in a separate thread so that it can immediately return. */
	public void quitJob(final String name) {
		new Thread () { public void run() { setPriority(Thread.NORM_PRIORITY);
		Object ob = null;
		synchronized (popup_lock) {
			while (popup_locked) try { popup_lock.wait(); } catch (InterruptedException ie) {}
			popup_locked = true;
			if (null == name && al_jobs.size() > 0) {
				ob = al_jobs.get(al_jobs.size()-1);
			}  else {
				int i = Integer.parseInt(name.substring(4, name.indexOf(':')));
				if (i >= 1 && i <= al_jobs.size()) ob = al_jobs.get(i-1); // starts at 1
			}
			popup_locked = false;
			popup_lock.notifyAll();
		}
		if (null != ob) {
			// will wait until worker returns
			((Bureaucrat)ob).quit(); // will require the lock
		}
		synchronized (popup_lock) {
			while (popup_locked) try { popup_lock.wait(); } catch (InterruptedException ie) {}
			popup_locked = true;
			popup_jobs = null;
			popup_locked = false;
			popup_lock.notifyAll();
		}
		Utils.showStatus("Job canceled.", false);
		}}.start();
	}

	public final void printMemState() {
		Utils.log2(new StringBuffer("mem in use: ").append((IJ.currentMemory() * 100.0f) / max_memory).append('%')
		                    .append("\n\timps: ").append(imps.size())
				    .append("\n\tmawts: ").append(mawts.size())
			   .toString());
	}

	/** Fixes paths before presenting them to the file system, in an OS-dependent manner. */
	protected final ImagePlus openImage(String path) {
		if (null == path) return null;
		try {
			// supporting samba networks
			if (path.startsWith("//")) {
				path = path.replace('/', '\\');
			}
			// debug:
			Utils.log2("opening image " + path);
			//Utils.printCaller(this, 25);

			return openImagePlus(path, 0);
		} catch (Exception e) {
			Utils.log("Could not open image at " + path);
			e.printStackTrace();
			return null;
		}
	}

	/** Tries up to MAX_RETRIES to open an ImagePlus at path if there is an OutOfMemoryError. */
	protected final ImagePlus openImagePlus(final String path) {
		return openImagePlus(path, 0);
	}

	private final ImagePlus openImagePlus(final String path, int retries) {
		while (retries < MAX_RETRIES) try {
				IJ.redirectErrorMessages();
				return opener.openImage(path);

				// TODO: Use windowless LOCI to bypass Opener class completely
			} catch (OutOfMemoryError oome) {
				Utils.log2("openImagePlus: recovering from OutOfMemoryError");
				recoverOOME(); // No need to unlock db_lock: all image loading calls are by design outside the db_lock.
				Thread.yield();
				// Retry:
				retries++;
			} catch (Throwable t) {
				// Don't retry
				IJError.print(t);
				break;
			}
		return null;
	}

	/** Equivalent to File.getName(), does not subtract the slice info from it.*/
	protected final String getInternalFileName(Patch p) {
		final String path = getAbsolutePath(p);
		if (null == path) return null;
		int i = path.length() -1;
		// Safer than lastIndexOf: never returns -1
		while (i > -1) {
			if ('/' == path.charAt(i)) {
				break;
			}
			i--;
		}
		return path.substring(i+1);
	}

	/** Equivalent to File.getName(), but subtracts the slice info from it if any.*/
	public final String getFileName(final Patch p) {
		String name = getInternalFileName(p);
		int i = name.lastIndexOf("-----#slice=");
		if (-1 == i) return name;
		return name.substring(0, i);
	}

	/** Check if an awt exists to paint as a snap. */
	public boolean isSnapPaintable(final long id) {
		synchronized (db_lock) {
			lock();
			if (mawts.contains(id)) {
				unlock();
				return true;
			}
			unlock();
		}
		return false;
	}

	/** If mipmaps regeneration is enabled or not. */
	protected boolean mipmaps_regen = true;

	// used to prevent generating them when, for example, importing a montage
	public void setMipMapsRegeneration(boolean b) {
		mipmaps_regen = b;
	}

	/** Does nothing unless overriden. */
	public void flushMipMaps(boolean forget_dir_mipmaps) {}

	/** Does nothing unless overriden. */
	public void flushMipMaps(final long id) {}

	/** Does nothing and returns false unless overriden. */
	protected boolean generateMipMaps(final Patch patch) { return false; }

	/** Does nothing unless overriden. */
	public void removeMipMaps(final Patch patch) {}

	/** Returns generateMipMaps(al, false). */
	public Bureaucrat generateMipMaps(final ArrayList al) {
		return generateMipMaps(al, false);
	}

	/** Does nothing and returns null unless overriden. */
	public Bureaucrat generateMipMaps(final ArrayList al, boolean overwrite) { return null; }

	/** Does nothing and returns false unless overriden. */
	public boolean isMipMapsEnabled() { return false; }

	/** Does nothing and returns zero unless overriden. */
	public int getClosestMipMapLevel(final Patch patch, int level) {return 0;}

	/** Does nothing and returns null unless overriden. */
	protected Image fetchMipMapAWT(final Patch patch, final int level, final long n_bytes) { return null; }

	/** Does nothing and returns false unless overriden. */
	public boolean checkMipMapFileExists(Patch p, double magnification) { return false; }

	public void adjustChannels(final Patch p, final int old_channels) {
		/*
		if (0xffffffff == old_channels) {
			// reuse any loaded mipmaps
			Hashtable<Integer,Image> ht = null;
			synchronized (db_lock) {
				lock();
				ht = mawts.getAll(p.getId());
				unlock();
			}
			for (Map.Entry<Integer,Image> entry : ht.entrySet()) {
				// key is level, value is awt
				final int level = entry.getKey();
				PatchLoadingLock plock = null;
				synchronized (db_lock) {
					lock();
					plock = getOrMakePatchLoadingLock(p, level);
					unlock();
				}
				synchronized (plock) {
					plock.lock(); // block loading of this file
					Image awt = null;
					try {
						awt = p.adjustChannels(entry.getValue());
					} catch (Exception e) {
						IJError.print(e);
						if (null == awt) continue;
					}
					synchronized (db_lock) {
						lock();
						mawts.replace(p.getId(), awt, level);
						removePatchLoadingLock(plock);
						unlock();
					}
					plock.unlock();
				}
			}
		} else {
		*/
			// flush away any loaded mipmap for the id
			synchronized (db_lock) {
				lock();
				mawts.removeAndFlush(p.getId());
				unlock();
			}
			// when reloaded, the channels will be adjusted
		//}
	}

	static public ImageProcessor scaleImage(final ImagePlus imp, double mag, final boolean quality) {
		if (mag > 1) mag = 1;
		ImageProcessor ip = imp.getProcessor();
		if (Math.abs(mag - 1) < 0.000001) return ip;
		// else, make a properly scaled image:
		//  - gaussian blurred for best quality when resizing with nearest neighbor
		//  - direct nearest neighbor otherwise
		final int w = ip.getWidth();
		final int h = ip.getHeight();
		// TODO releseToFit !
		if (quality) {
			// apply proper gaussian filter
			double sigma = Math.sqrt(Math.pow(2, getMipMapLevel(mag, Math.max(imp.getWidth(), imp.getHeight()))) - 0.25); // sigma = sqrt(level^2 - 0.5^2)
			ip = new FloatProcessorT2(w, h, ImageFilter.computeGaussianFastMirror(new FloatArray2D((float[])ip.convertToFloat().getPixels(), w, h), (float)sigma).data, ip.getDefaultColorModel(), ip.getMin(), ip.getMax());
			ip = ip.resize((int)(w * mag), (int)(h * mag)); // better while float
			return Utils.convertTo(ip, imp.getType(), false);
		} else {
			return ip.resize((int)(w * mag), (int)(h * mag));
		}
	}

	static public ImageProcessor scaleImage(final ImagePlus imp, final int level, final boolean quality) {
		if (level <= 0) return imp.getProcessor();
		// else, make a properly scaled image:
		//  - gaussian blurred for best quality when resizing with nearest neighbor
		//  - direct nearest neighbor otherwise
		ImageProcessor ip = imp.getProcessor();
		final int w = ip.getWidth();
		final int h = ip.getHeight();
		final double mag = 1 / Math.pow(2, level);
		// TODO releseToFit !
		if (quality) {
			// apply proper gaussian filter
			double sigma = Math.sqrt(Math.pow(2, level) - 0.25); // sigma = sqrt(level^2 - 0.5^2)
			ip = new FloatProcessorT2(w, h, ImageFilter.computeGaussianFastMirror(new FloatArray2D((float[])ip.convertToFloat().getPixels(), w, h), (float)sigma).data, ip.getDefaultColorModel(), ip.getMin(), ip.getMax());
			ip = ip.resize((int)(w * mag), (int)(h * mag)); // better while float
			return Utils.convertTo(ip, imp.getType(), false);
		} else {
			return ip.resize((int)(w * mag), (int)(h * mag));
		}
	}

	/* =========================== */

	/** Serializes the given object into the path. Returns false on failure. */
	public boolean serialize(final Object ob, final String path) {
		try {
			// 1 - Check that the parent chain of folders exists, and attempt to create it when not:
			File fdir = new File(path).getParentFile();
			if (null == fdir) return false;
			fdir.mkdirs();
			if (!fdir.exists()) {
				Utils.log2("Could not create folder " + fdir.getAbsolutePath());
				return false;
			}
			// 2 - Serialize the given object:
			final ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(path));
			out.writeObject(ob);
			out.close();
			return true;
		} catch (Exception e) {
			IJError.print(e);
		}
		return false;
	}
	/** Attempts to find a file containing a serialized object. Returns null if no suitable file is found, or an error occurs while deserializing. */
	public Object deserialize(final String path) {
		try {
			if (!new File(path).exists()) return null;
			final ObjectInputStream in = new ObjectInputStream(new FileInputStream(path));
			final Object ob = in.readObject();
			in.close();
			return ob;
		} catch (Exception e) {
			//IJError.print(e); // too much output if a whole set is wrong
			e.printStackTrace();
		}
		return null;
	}

	public void insertXMLOptions(StringBuffer sb_body, String indent) {}

	/** Homogenize contrast layer-wise, for all given layers. */
	public Bureaucrat enhanceContrast(final Collection<Layer> layers) {
		if (null == layers || 0 == layers.size()) return null;
		return Bureaucrat.createAndStart(new Worker.Task("Enhancing contrast") {
			public void exec() {
				ContrastEnhancerWrapper cew = new ContrastEnhancerWrapper();
				if (!cew.showDialog()) return;
				cew.applyLayerWise(layers);
				cew.shutdown();
			}
		}, layers.iterator().next().getProject());
	}

	/** Homogenize contrast for all patches, optionally using the @param reference Patch (can be null). */
	public Bureaucrat enhanceContrast(final Collection<Displayable> patches, final Patch reference) {
		if (null == patches || 0 == patches.size()) return null;
		return Bureaucrat.createAndStart(new Worker.Task("Enhancing contrast") {
			public void exec() {
				ContrastEnhancerWrapper cew = new ContrastEnhancerWrapper(reference);
				if (!cew.showDialog()) return;
				cew.apply(patches);
				cew.shutdown();
			}
		}, patches.iterator().next().getProject());
	}

	public Bureaucrat setMinAndMax(final Collection<? extends Displayable> patches, final double min, final double max) {
		Worker worker = new Worker("Set min and max") {
			public void run() {
				try {
					startedWorking();
					if (Double.isNaN(min) || Double.isNaN(max)) {
						Utils.log("WARNING:\nUnacceptable min and max values: " + min + ", " + max);
						finishedWorking();
						return;
					}
					ArrayList<Future> fus = new ArrayList<Future>();
					for (final Displayable d : patches) {
						if (d.getClass() != Patch.class) continue;
						Patch p = (Patch)d;
						p.setMinAndMax(min, max);
						fus.add(regenerateMipMaps(p));
					}
					Utils.wait(fus);
				} catch (Exception e) {
					IJError.print(e);
				} finally {
					finishedWorking();
				}
			}
		};
		return Bureaucrat.createAndStart(worker, Project.findProject(this));
	}

	public long estimateImageFileSize(final Patch p, final int level) {
		if (0 == level) {
			return (long)(p.getWidth() * p.getHeight() * 5 + 1024); // conservative
		}
		// else, compute scale
		final double scale = 1 / Math.pow(2, level);
		return (long)(p.getWidth() * scale * p.getHeight() * scale * 5 + 1024); // conservative
	}

	// Dummy class to provide access the notifyListeners from Image
	static private final class ImagePlusAccess extends ImagePlus {
		final int CLOSE = CLOSED; // from super class ImagePlus
		final int OPEN = OPENED;
		final int UPDATE = UPDATED;
		private Vector<ij.ImageListener> my_listeners;
		public ImagePlusAccess() {
			super();
			try {
				java.lang.reflect.Field f = ImagePlus.class.getDeclaredField("listeners");
				f.setAccessible(true);
				this.my_listeners = (Vector<ij.ImageListener>)f.get(this);
			} catch (Exception e) {
				IJError.print(e);
			}
		}
		public final void notifyListeners(final ImagePlus imp, final int action) {
			try {
				for (ij.ImageListener listener : my_listeners) {
					switch (action) {
						case CLOSED:
							listener.imageClosed(imp);
							break;
						case OPENED:
							listener.imageOpened(imp);
							break;
						case UPDATED: 
							listener.imageUpdated(imp);
							break;
					}
				}
			} catch (Exception e) {}
		}
	}
	static private final ImagePlusAccess ipa = new ImagePlusAccess();

	/** Workaround for ImageJ's ImagePlus.flush() method which calls the System.gc() unnecessarily.<br />
	 * A null pointer as argument is accepted. */
	static public final void flush(final ImagePlus imp) {
		if (null == imp) return;
		final Roi roi = imp.getRoi();
		if (null != roi) roi.setImage(null);
		//final ImageProcessor ip = imp.getProcessor(); // the nullifying makes no difference, and in low memory situations some bona fide imagepluses may end up failing on the calling method because of lack of time to grab the processor etc.
		//if (null != ip) ip.setPixels(null);
		ipa.notifyListeners(imp, ipa.CLOSE);
	}

	/** Returns the user's home folder unless overriden. */
	public String getStorageFolder() { return System.getProperty("user.home").replace('\\', '/'); }

	public String getImageStorageFolder() { return getStorageFolder(); }

	/** Returns null unless overriden. */
	public String getMipMapsFolder() { return null; }

	public Patch addNewImage(final ImagePlus imp) {
		return addNewImage(imp, 0, 0);
	}

	/** Mipmaps for this image are generated asynchronously. */
	public Patch addNewImage(final ImagePlus imp, final double x, final double y) {
		String filename = imp.getTitle();
		if (!filename.toLowerCase().endsWith(".tif")) filename += ".tif";
		String path = getStorageFolder() + "/" + filename;
		new FileSaver(imp).saveAsTiff(path);
		Patch pa = new Patch(Project.findProject(this), imp.getTitle(), x, y, imp);
		addedPatchFrom(path, pa);
		if (isMipMapsEnabled()) regenerateMipMaps(pa);
		return pa;
	}

	public String makeProjectName() {
		return "Untitled " + ControlWindow.getTabIndex(Project.findProject(this));
	}


	/** Will preload in the background as many as possible of the given images for the given magnification, if and only if (1) there is more than one CPU core available [and only the extra ones will be used], and (2) there is more than 1 image to preload. */

	static private ExecutorService preloader = null;
	static private final LinkedList<Runnable> preloads = new LinkedList<Runnable>();
	static private final Object PL = new Object();

	static public final void setupPreloader(final ControlWindow master) {
		if (null == preloader) {
			int n = Runtime.getRuntime().availableProcessors()-1;
			if (0 == n) n = 1; // !@#$%^
			preloader = Executors.newFixedThreadPool(n);
			for (int i=0; i<n; i++) {
				preloader.submit(new Callable() {
					public Object call() {
						Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
						while (!Thread.currentThread().isInterrupted()) {
							try {
								synchronized (PL) {
									if (preloads.isEmpty()) try {
										PL.wait();
									} catch (InterruptedException ie) {
										// Thread was terminated
										return null;
									}
								}
								Runnable r;
								synchronized (preloads) {
									try {
										r = preloads.removeLast();
									} catch (NoSuchElementException nsee) {
										// Empty list!
										return null;
									}
								}
								if (null != r) r.run();
							} catch (Throwable t) {
								t.printStackTrace();
							}
						}
						return null;
					}
				});
			}
		}
	}
	static public final void destroyPreloader(final ControlWindow master) {
		preloads.clear();
		if (null != preloader) { preloader.shutdownNow(); preloader = null; }
	}

	static public void preload(final Collection<Patch> patches, final double mag, final boolean repaint) {
		for (final Patch p : patches) {
			preload(p, mag, repaint);
		}
	}
	static public void preload(final Patch p, final double mag, final boolean repaint) {
		synchronized (preloads) {
			preloads.add(new Runnable() {
				public void run() {
					try {
						if (p.getProject().getLoader().hs_unloadable.contains(p)) return;
						if (repaint) {
							if (Display.willPaint(p, mag)) {
								Object ob = p.getProject().getLoader().fetchImage(p, mag);
								if (null != ob) Display.repaint(p.getLayer(), p, p.getBoundingBox(null), 1, false); // not the navigator
							}
						} else {
							// just load it into the cache if possible
							p.getProject().getLoader().fetchImage(p, mag);
						}
					} catch (Throwable e) { e.printStackTrace(); }}});
		}
		synchronized (PL) { PL.notify(); }
	}

	/** Returns the highest mipmap level for which a mipmap image may have been generated given the dimensions of the Patch. The minimum that this method may return is zero. */
	public static final int getHighestMipMapLevel(final Patch p) {
		/*
		int level = 0;
		int w = (int)p.getWidth();
		int h = (int)p.getHeight();
		while (w >= 64 && h >= 64) {
			w /= 2;
			h /= 2;
			level++;
		}
		return level;
		*/
		// Analytically:
		// For images of width or height of at least 32 pixels, need to test for log(64) like in the loop above
		// because this is NOT a do/while but a while, so we need to stop one step earlier.
		return (int)(0.5 + (Math.log(Math.min(p.getWidth(), p.getHeight())) - Math.log(64)) / Math.log(2));
		// Same as:
		// return getHighestMipMapLevel(Math.min(p.getWidth(), p.getHeight()));
	}

	public static final int getHighestMipMapLevel(final double size) {
		return (int)(0.5 + (Math.log(size) - Math.log(64)) / Math.log(2));
	}

	static public final int NEAREST_NEIGHBOR = 0;
	static public final int BILINEAR = 1;
	static public final int BICUBIC = 2;
	static public final int GAUSSIAN = 3;
	static public final int AREA_AVERAGING = 4;
	static public final String[] modes = new String[]{"Nearest neighbor", "Bilinear", "Bicubic", "Gaussian"}; //, "Area averaging"};

	static public final int getMode(final String mode) {
		for (int i=0; i<modes.length; i++) {
			if (mode.equals(modes[i])) return i;
		}
		return 0;
	}

	/** Does nothing unless overriden. */
	public Bureaucrat generateLayerMipMaps(final Layer[] la, final int starting_level) {
		return null;
	}

	/** Recover from an OutOfMemoryError: release 1/2 of all memory AND execute the garbage collector. */
	public void recoverOOME() {
		releaseToFit(IJ.maxMemory() / 2);
		long start = System.currentTimeMillis();
		long end = start;
		for (int i=0; i<3; i++) {
			System.gc();
			Thread.yield();
			end = System.currentTimeMillis();
			if (end - start > 2000) break; // garbage collecion catched and is running.
			start = end;
		}
	}

	static public boolean canReadAndWriteTo(final String dir) {
		final File fsf = new File(dir);
		return fsf.canWrite() && fsf.canRead();
	}


	/** Does nothing and returns null unless overridden. */
	public String setImageFile(Patch p, ImagePlus imp) { return null; }

	public boolean isUnloadable(final Displayable p) { return hs_unloadable.contains(p); }

	public void removeFromUnloadable(final Displayable p) { hs_unloadable.remove(p); }

	protected static final BufferedImage createARGBImage(final int width, final int height, final int[] pix) {
		final BufferedImage bi = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		// In one step, set pixels that contain the alpha byte already:
		bi.setRGB( 0, 0, width, height, pix, 0, width );
		return bi;
	}

	/** Embed the alpha-byte into an int[], changes the int[] in place and returns it */
	protected static final int[] embedAlpha( final int[] pix, final byte[] alpha){
		return embedAlpha(pix, alpha, null);
	}

	protected static final int[] embedAlpha( final int[] pix, final byte[] alpha, final byte[] outside) {
		if (null == outside) {
			if (null == alpha)
				return pix;
			for (int i=0; i<pix.length; ++i)
				pix[i] = (pix[i]&0x00ffffff) | ((alpha[i]&0xff)<<24);
		} else {
			for (int i=0; i<pix.length; ++i) {
				pix[i] = (pix[i]&0x00ffffff) | ( (outside[i]&0xff) != 255  ? 0 : ((alpha[i]&0xff)<<24) );
			}
		}
		return pix;
	}

	/** Does nothing unless overriden. */
	public void queueForMipmapRemoval(final Patch p, boolean yes) {}

	/** Does nothing unless overriden. */
	public void tagForMipmapRemoval(final Patch p, boolean yes) {}

	/** Get the Universal Near-Unique Id for the project hosted by this loader. */
	public String getUNUId() {
		// FSLoader overrides this method
		return Long.toString(System.currentTimeMillis());
	}

	// FSLoader overrides this method
	public String getUNUIdFolder() {
		return "trakem2." + getUNUId() + "/";
	}

	/** Does nothing and returns null unless overriden. */
	public Future<Boolean> regenerateMipMaps(final Patch patch) { return null; }


	/** Does nothing and returns null unless overriden. */
	public Bureaucrat regenerateMipMaps(final Collection<? extends Displayable> patches) { return null; }

	/** Read out the width,height of an image using LOCI BioFormats. */
	static public Dimension getDimensions(final String path) {
		IFormatReader fr = null;
		try {
			fr = new ChannelSeparator();
			fr.setId(path);
			return new Dimension(fr.getSizeX(), fr.getSizeY());
		} catch (FormatException fe) {
			Utils.log("Error in reading image file at " + path + "\n" + fe);
		} catch (Exception e) {
			IJError.print(e);
		} finally {
			if (null != fr) try {
				fr.close();
			} catch (IOException ioe) { Utils.log2("Could not close IFormatReader: " + ioe); }
		}
		return null;
	}

	public Dimension getDimensions(final Patch p) {
		String path = getAbsolutePath(p);
		int i = path.lastIndexOf("-----#slice=");
		if (-1 != i) path = path.substring(0, i);
		return Loader.getDimensions(path);
	}

	/** Table of preprocessor scripts. */
	private Hashtable<Patch,String> preprocessors = new Hashtable<Patch,String>();

	/** Set a preprocessor script that will be executed on the ImagePlus of the Patch when loading it, before TrakEM2 sees it at all.  Automatically regenerates mipmaps
	 *  To remove the script, set it to null. */
	public void setPreprocessorScriptPath(final Patch p, final String path) {
		setPreprocessorScriptPathSilently( p, path );
		// If the ImagePlus is cached, it will not be preProcessed.
		// Merely running the preProcess on the cached image is no guarantee; threading competition may result in an unprocessed, newly loaded image.
		// Hence, decache right after setting the script, then update mipmaps
		decacheImagePlus(p.getId());
		regenerateMipMaps(p); // queued
	}
	
	/** Set a preprocessor script that will be executed on the ImagePlus of the Patch when loading it, before TrakEM2 sees it at all.
	 *  To remove the script, set it to null. */
	public void setPreprocessorScriptPathSilently(final Patch p, final String path) {
		if (null == path) preprocessors.remove(p);
		else preprocessors.put(p, path);
	}
	
	public String getPreprocessorScriptPath(final Patch p) {
		return preprocessors.get(p);
	}

	/** Returns @param path unless overriden. */
	public String makeRelativePath(String path) { return path; }

	/** Does nothing unless overriden. */
	public String getParentFolder() { return null; }
	
	// Will be shut down by Loader.destroy()
	private final ExecutorService exec = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
	
	public < T > Future< T > doLater( final Callable< T > fn ) {
		return exec.submit( fn );
	}

	// Will be shut down by Loader.destroy()
	private final Dispatcher guiExec = new Dispatcher("GUI Executor");

	/** Execute a GUI-related task later; it's the fn's responsability to do the call via SwingUtilities.invokeLater if necesary. */
	public void doGUILater( final boolean swing, final Runnable fn ) {
		guiExec.exec( fn, swing );
	}

	/** Make the border have an alpha of zero. */
	public Bureaucrat maskBordersLayerWise(final Collection<Layer> layers, final int top, final int right, final int bottom, final int left) {
		return Bureaucrat.createAndStart(new Worker.Task("Crop borders") {
			public void exec() {
				ArrayList<Future> fus = new ArrayList<Future>();
				for (final Layer layer : layers) {
					fus.addAll(maskBorders(top, right, bottom, left, layer.getDisplayables(Patch.class)));
				}
				Utils.wait(fus);
			}
		}, layers.iterator().next().getProject());
	}

	/** Make the border have an alpha of zero. */
	public Bureaucrat maskBorders(final Collection<Displayable> patches, final int top, final int right, final int bottom, final int left) {
		return Bureaucrat.createAndStart(new Worker.Task("Crop borders") {
			public void exec() {
				Utils.wait(maskBorders(top, right, bottom, left, patches));
			}
		}, patches.iterator().next().getProject());
	}

	/** Make the border have an alpha of zero.
	 *  @return the list of Future that represent the regeneration of the mipmaps of each Patch. */
	public ArrayList<Future> maskBorders(final int top, final int right, final int bottom, final int left, final Collection<Displayable> patches) {
		ArrayList<Future> fus = new ArrayList<Future>();
		for (final Displayable d : patches) {
			if (d.getClass() != Patch.class) continue;
			Patch p = (Patch) d;
			if (p.maskBorder(top, right, bottom, left)) {
				fus.add(regenerateMipMaps(p));
			}
		}
		return fus;
	}

	/** Returns an ImageStack, one slice per region. */
	public ImagePlus createFlyThrough(final List<Region> regions, final double magnification, final int type) {
		ExecutorService ex = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
		List<Future<ImagePlus>> fus = new ArrayList<Future<ImagePlus>>();
		for (final Region r : regions) {
			fus.add(ex.submit(new Callable<ImagePlus>() {
				public ImagePlus call() {
					return getFlatImage(r.layer, r.r, magnification, 0xffffffff, type, Displayable.class, null, true, Color.black);
				}
			}));
		}
		Region r = regions.get(0);
		ImageStack stack = new ImageStack((int)(r.r.width * magnification), (int)(r.r.height * magnification));
		for (int i=0; i<regions.size(); i++) {
			try {
				if (Thread.currentThread().isInterrupted()) break;
				stack.addSlice(regions.get(i).layer.toString(), fus.get(i).get().getProcessor());
			} catch (Throwable t) {
				IJError.print(t);
			}
		}
		ex.shutdown();
		return new ImagePlus("Fly-Through", stack);
	}
}
