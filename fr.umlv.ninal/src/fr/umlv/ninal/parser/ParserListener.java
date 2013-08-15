package fr.umlv.ninal.parser;

import fr.umlv.ninal.lang.List;
import fr.umlv.ninal.lang.Symbol;

public interface ParserListener {
  public Object parseList(List list);
  public Object parseNumber(Number number);
  public Object parseString(String string);
  public Object parseSymbol(Symbol symbol);
}
