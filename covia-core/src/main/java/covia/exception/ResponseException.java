package covia.exception;

@SuppressWarnings("serial")
public class ResponseException extends RuntimeException {

	public final Object response;
	
	public ResponseException(String message, Object response) {
		super(message);
		this.response=response;
	}

}
