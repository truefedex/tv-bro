package com.phlox.tvwebbrowser.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class StringUtils {
	public static String streamToString(InputStream stream) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		int read = 0;
		byte[] buffer = new byte[1024];
		while ((read = stream.read(buffer)) > -1) {
			output.write(buffer, 0, read);
		}
		String result = new String(output.toByteArray());
		output.close();
		return result;
	}
}
