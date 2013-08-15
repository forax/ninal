package fr.umlv.ninal.lang;

public class Symbol {
  private final String name;

  public Symbol(String name) {
    this.name = name;
  }
  
  public String getName() {
    return name;
  }
  
  @Override
  public int hashCode() {
    return name.hashCode();
  }
  
  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Symbol)) {
      return false;
    }
    return name.equals(((Symbol)o).name);
  }
  
  @Override
  public String toString() {
    return ':' + name;
  }
}
