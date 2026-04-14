package com.rs.utils.reflect;

public class ReflectionResponse {

	public enum ResponseCode {
		SUCCESS,
		CLASS_NOT_FOUND,
		FIELD_NOT_FOUND,
		METHOD_NOT_FOUND,
		INVOCATION_ERROR,
		NUMBER,
		STRING,
		UNKNOWN;

		public static ResponseCode forId(int id) {
			ResponseCode[] values = values();
			return id >= 0 && id < values.length ? values[id] : UNKNOWN;
		}
	}

	private ResponseCode code;
	private long data;
	private String stringData;

	public ReflectionResponse(ResponseCode code) {
		this.code = code;
	}

	public ResponseCode getCode() {
		return code;
	}

	public long getData() {
		return data;
	}

	public void setData(long data) {
		this.data = data;
	}

	public String getStringData() {
		return stringData;
	}

	public void setStringData(String stringData) {
		this.stringData = stringData;
	}

	@Override
	public String toString() {
		return "{ " + code + ", " + data + ", " + stringData + " }";
	}
}
