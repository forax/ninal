package fr.umlv.ninal.parser;

public class ParsingException extends RuntimeException {
  private static final long serialVersionUID = -4885249198393843230L;

  public ParsingException() {
    super();
  }

  public ParsingException(String message, Throwable cause) {
    super(message, cause);
  }

  public ParsingException(String message) {
    super(message);
  }

  public ParsingException(Throwable cause) {
    super(cause);
  }
}
