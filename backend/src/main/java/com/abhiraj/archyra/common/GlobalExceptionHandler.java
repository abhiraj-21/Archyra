package com.abhiraj.archyra.common;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Single place that maps exceptions to {@link ErrorResponse} with the right HTTP status.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
		String message = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> error.getDefaultMessage())
				.sorted()
				.collect(Collectors.joining("; "));
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ErrorResponse("VALIDATION_ERROR", message));
	}

	/** A query parameter that cannot be converted, e.g. an unknown {@code nodeType}. The value is not echoed. */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleInvalidParameter(MethodArgumentTypeMismatchException ex) {
		Class<?> type = ex.getRequiredType();
		String message = "Invalid value for parameter '" + ex.getName() + "'";
		if (type != null && type.isEnum()) {
			message += ". Allowed values: " + Arrays.stream(type.getEnumConstants())
					.map(constant -> ((Enum<?>) constant).name()).collect(Collectors.joining(", "));
		}
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("VALIDATION_ERROR", message));
	}

	@ExceptionHandler(InvalidRequestException.class)
	public ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidRequestException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("VALIDATION_ERROR", ex.getMessage()));
	}

	@ExceptionHandler(NodeNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleNodeNotFound(NodeNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ErrorResponse("NODE_NOT_FOUND", ex.getMessage()));
	}

	@ExceptionHandler(AnalysisNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleAnalysisNotFound(AnalysisNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ErrorResponse("ANALYSIS_NOT_FOUND", ex.getMessage()));
	}

	@ExceptionHandler(AnalysisNotCompletedException.class)
	public ResponseEntity<ErrorResponse> handleAnalysisNotCompleted(AnalysisNotCompletedException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ErrorResponse("ANALYSIS_NOT_COMPLETED", ex.getMessage()));
	}

	@ExceptionHandler(ServiceBusyException.class)
	public ResponseEntity<ErrorResponse> handleBusy(ServiceBusyException ex) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(new ErrorResponse("SERVICE_BUSY", ex.getMessage()));
	}

	/** Missing, empty or malformed JSON body. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ErrorResponse("INVALID_REQUEST_BODY", "Request body is missing or malformed"));
	}
}
