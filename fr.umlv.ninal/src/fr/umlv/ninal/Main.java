package fr.umlv.ninal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import fr.umlv.ninal.interpreter.Interpreter;
import fr.umlv.ninal.lang.List;
import fr.umlv.ninal.parser.Parser;

public class Main {
  public static void main(String[] args) throws IOException {
    /*
    byte[] data = Files.readAllBytes(Paths.get("ninal/test.ninal"));
    Parser parser = new Parser(data);
    while(!parser.end()) {
      List list = parser.parseList();
      System.out.println(list);  
    }*/
    
    Interpreter interpreter = new Interpreter();
    interpreter.interpret(Paths.get("ninal/test.ninal"));
  }

}
