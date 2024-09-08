package com.vagell.kv4pht.javAX25.ax25;

public class Arrays {
	public static byte[] copyOfRange(byte[] src, int from, int to) {
		byte[] dest = new byte[to-from];
		System.arraycopy(src, from, dest, 0, to-from);
		return dest;
	}
	public static byte[] copyOf(byte[] src, int count) {
		return copyOfRange(src, 0, count);
	}
	public static String[] copyOfRange(String[] src, int from, int to) {
		String[] dest = new String[to-from];
		//System.out.println(">> "+from+" , "+to+" len="+src.length+"  new len="+(to-from));
		int len = to-from;
		if (len > src.length) len = src.length - from;
		System.arraycopy(src, from, dest, 0, len);
		return dest;
	}
	public static String[] copyOf(String[] src, int count) {
		return copyOfRange(src, 0, count);
	}
	public static boolean equals(byte[] a, byte[] b) {
		if (a==null && b==null) return true;
		if (a==null || b==null) return false;
		if (a.length != b.length) return false;
		for (int i=0; i<a.length; i++)
			if (a[i] != b[i]) return false;
		return true;
	}
}
