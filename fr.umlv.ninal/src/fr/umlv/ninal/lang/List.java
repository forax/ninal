package fr.umlv.ninal.lang;

import java.util.Arrays;

public class List extends java.util.AbstractList<Object> implements java.util.RandomAccess {
  private final Object[] array;

  List(Object[] array) {
    this.array = array;
  }

  public static class Builder {
    private Object[] array;
    private int size;

    public Builder() {
      array = new Object[8];
    }

    @Override
    public int hashCode() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean equals(Object obj) {
      throw new UnsupportedOperationException();
    }

    public Builder append(Object o) {
      if (array.length == size) {
        array = Arrays.copyOf(array, array.length << 1);
      }
      array[size++] = o;
      return this;
    }

    public List toList() {
      return new List(Arrays.copyOf(array, size));
    }

    @Override
    public String toString() {
      return toList().toString();
    }
  }

  @Override
  public Object get(int index) {
    return array[index];
  }

  @Override
  public int size() {
    return array.length;
  }

  @Override
  public List subList(int fromIndex, int toIndex) {
    return new List(Arrays.copyOfRange(array, fromIndex, toIndex));
  }

  public static List empty() {
    return EMPTY;
  }
  private static final List EMPTY = new List(new Object[0]);

  public static List of(Object o) {
    return new List(new Object[]{o});
  }
  public static List of(Object o1, Object o2) {
    return new List(new Object[]{o1, o2});
  }
  public static List of(Object o1, Object o2, Object o3) {
    return new List(new Object[]{o1, o2, o3});
  }
  public static List of(Object... array) {
    if (array.length == 0) {
      return EMPTY;
    }
    return new List(Arrays.copyOf(array, array.length));
  }
}
