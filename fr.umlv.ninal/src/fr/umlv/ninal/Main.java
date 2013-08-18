package fr.umlv.ninal;

import java.io.IOException;
import java.nio.file.Paths;

import fr.umlv.ninal.interpreter.Interpreter;

public class Main {
  public static void main(String[] args) throws IOException {
    Interpreter interpreter = new Interpreter();
    interpreter.interpret(Paths.get(args[0]));
  }
}
