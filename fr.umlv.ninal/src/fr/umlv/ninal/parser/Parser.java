package fr.umlv.ninal.parser;

import java.math.BigInteger;

import fr.umlv.ninal.lang.List;
import fr.umlv.ninal.lang.Symbol;
import fr.umlv.ninal.lang.List.Builder;

public class Parser {
  private static final char EOF = '\n';
  
  private final byte[] data;
  private int index;
  
  public Parser(byte[] data) {
    this.data = data;
  }
  
  public boolean end() {
    return current(true) == EOF;
  }
  
  private char current(boolean skipSpace) {
    for(;;) {
      if (index == data.length) {
        return EOF;   // fake end of line
      }
      int c = data[index] & 0xff;
      if (c > 127) { // FIXME, support UTF8
        throw new ParsingException("not an ASCII char !");
      }
      if (skipSpace && (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == ',')) {
        index++;
        continue;
      }
      return (char)c;
    }
  }
  
  private void checkCurrentLetter(char letter) {
    char c = current(true);
    if (c != letter) {
      if (c == EOF) {
        throw new ParsingException("premature end of file");
      }
      throw new ParsingException("waiting for " + letter + " but found " + c);
    }
  }
  
  public List parseList() throws ParsingException {
    checkCurrentLetter('(');
    index++;
    List.Builder builder = new List.Builder();
    for(;;) {
      char c = current(true);
      if (c == ')') {
        break;
      }
      if (c == EOF) {
        throw new ParsingException("premature end of file");
      }
      builder.append(parseAtom());
    }
    index++;
    return builder.toList();
  }
  
  private Object parseAtom() {
    char c = current(true);
    switch(c) {
    case '(':
      return parseList();
    case EOF:
      throw new ParsingException("premature end of file");
    case '0': case '1': case '2': case '3':
    case '4': case '5': case '6': case '7':
    case '8': case '9':
      return parseNumber(c);
    case '\'': case '"':
      return parseString(c);
    default:
      return parseSymbol(c);
    }
  }
  
  private Number parseNumber(char firstDigit) {
    StringBuilder builder = new StringBuilder();
    builder.append(firstDigit);
    index++;
    for(;;) {
      char c = current(false);
      switch(c) {
      case '0': case '1': case '2': case '3':
      case '4': case '5': case '6': case '7':
      case '8': case '9':
        builder.append(c);
        index++;
        continue;
        
      default:
        String text = builder.toString();
        try {
          return Integer.parseInt(text);
        } catch(NumberFormatException e) {
          return new BigInteger(text, 10);
        }
      }
    }
  }
  
  private String parseString(char firstLetter) {
    StringBuilder builder = new StringBuilder();
    index++;
    char c;
    while((c = current(false)) != firstLetter) {
      builder.append(c);
      index++;
    }
    index++;
    return builder.toString();
  }
  
  private Symbol parseSymbol(char firstLetter) {
    StringBuilder builder = new StringBuilder();
    builder.append(firstLetter);
    index++;
    for(;;) {
      char c = current(false);
      switch(c) {
      
      default:
        builder.append(c);
        index++;
        continue;
        
      case ')': case ' ': case '\t': case '\r': case '\n': case ',':
        return new Symbol(builder.toString());
      }
    }
  }
}
