package fr.umlv.ninal.interpreter;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import com.oracle.truffle.api.Arguments;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.SlowPath;
import com.oracle.truffle.api.ExactMath;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleRuntime;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.impl.DefaultVirtualFrame;
import com.oracle.truffle.api.nodes.NodeUtil;

import fr.umlv.ninal.lang.List;
import fr.umlv.ninal.lang.Symbol;
import fr.umlv.ninal.parser.Parser;

public class Interpreter {
  static abstract class Node extends com.oracle.truffle.api.nodes.Node {
    protected Node() {
      super();
    }
    
    abstract Object eval(VirtualFrame frame);
  }
  
  static class ConstNode extends Node {
    private final Object constant;
    
    ConstNode(Object constant) {
      this.constant = constant;
    }

    @Override
    public Object eval(VirtualFrame frame) {
      return constant;
    }
  }
  
  static class LiteralListNode extends Node {
    @Children
    private final Node[] valueNodes;
    
    LiteralListNode(Node[] nodes) {
      this.valueNodes = adoptChildren(nodes);
    }

    @Override
    public Object eval(VirtualFrame frame) {
      Object[] values = new Object[valueNodes.length];
      for(int i=0; i<values.length; i++) {
        values[i] = valueNodes[i].eval(frame);
      }
      return List.of(values);
    }
  }
    
  static class EvalNode extends com.oracle.truffle.api.nodes.RootNode {
    @Child
    private final Node bodyNode;

    EvalNode(Node bodyNode) {
      this.bodyNode = adoptChild(bodyNode);
    }
    
    @Override
    public Object execute(VirtualFrame frame) {
      return bodyNode.eval(frame);
    }
  }
  
  static class FunctionNode extends com.oracle.truffle.api.nodes.RootNode {
    private final Symbol symbol;
    private final FrameSlot[] slots;
    @Child
    private final Node bodyNode;
    
    FunctionNode(Symbol symbol, FrameSlot[] slots, Node bodyNode) {
      this.symbol = symbol;
      this.slots = slots;
      this.bodyNode = adoptChild(bodyNode);
    }
    
    @Override
    public Object execute(VirtualFrame frame) {
      ArrayArguments arguments = frame.getArguments(ArrayArguments.class);
      if (slots.length != arguments.size()) {
        throw new RuntimeException("invalid number of arguments for function call " + symbol);
      }
      
      for(int i=0; i<arguments.size(); i++) {
        try {
          frame.setObject(slots[i], arguments.get(i));
        } catch (FrameSlotTypeException e) {
          throw new RuntimeException(e);
        }
      }
      return bodyNode.eval(frame);
    }
  }
  
  /*non-static*/ class DefNode extends Node {
    private final Symbol name;
    private final FrameDescriptor functionFrameDescriptor;
    private final FrameSlot[] slots;
    private final Node bodyNode;
    
    DefNode(Symbol name, FrameDescriptor functionFrameDescriptor, FrameSlot[] slots, Node bodyNode) {
      this.name = name;
      this.functionFrameDescriptor = functionFrameDescriptor;
      this.slots = slots;
      this.bodyNode = adoptChild(bodyNode);
    }

    public Symbol getName() {
      return name;
    }
    
    @Override
    public Object eval(VirtualFrame frame) {
      FunctionNode functionNode = new FunctionNode(name, slots, bodyNode);
      CallTarget callTarget = Truffle.getRuntime().createCallTarget(functionNode, functionFrameDescriptor);
      callTargetMap.put(name, callTarget);
      
      return List.empty();
    }
  }
  
  /*non-static*/ class FunCallNode extends Node {
    private final Symbol name;
    @Children
    private final Node[] argumentNodes;
    
    FunCallNode(Symbol name, Node[] argumentNodes) {
      this.name = name;
      this.argumentNodes = adoptChildren(argumentNodes);
    }

    @Override
    public Object eval(VirtualFrame frame) {
      Object[] arguments = new Object[argumentNodes.length];
      for(int i=0; i<argumentNodes.length; i++) {
        arguments[i] = argumentNodes[i].eval(frame);
      }
      
      CallTarget callTarget = callTargetMap.get(name);
      return callTarget.call(frame.pack(), new ArrayArguments(arguments));
    }
  }
  
  //FIXME should be List ?
  static class ArrayArguments extends Arguments {
    private final Object[] values;

    ArrayArguments(Object[] values) {
        this.values = values;
    }

    int size() {
      return values.length;
    }
    
    Object get(int index) {
        return values[index];
    }
  }
  
  enum BinOp {
    ADD("+"), SUB("-"), MUL("*"), DIV("/"),
    LT("<"), GT(">"), LE("<="), GE(">=");

    private final String name;
    
    private BinOp(String name) {
      this.name = name;
    }
    
    private static final HashMap<String, BinOp> MAP;
    static {
      HashMap<String, BinOp> map = new HashMap<>();
      for(BinOp binOp: BinOp.values()) {
        map.put(binOp.name,  binOp);
      }
      MAP = map;
    }
    
    static BinOp getBinOp(String name) {
      return MAP.get(name);
    }
  }
  
  static class NumberOpNode extends Node {
    private final BinOp binOp;
    @Child
    private final Node leftNode;
    @Child
    private final Node rightNode;
    
    NumberOpNode(BinOp binOp, Node leftNode, Node rightNode) {
      this.binOp = binOp;
      this.leftNode = adoptChild(leftNode);
      this.rightNode = adoptChild(rightNode);
    }

    @Override
    public Object eval(VirtualFrame frame) {
      Object leftValue = leftNode.eval(frame);
      Object rightValue = rightNode.eval(frame);
      if (leftValue instanceof Integer && rightValue instanceof Integer) {
        return doSmallOp(leftValue, rightValue);
      }
      return slowPath(leftValue, rightValue);
    }
    
    private BigInteger slowPath(Object leftValue, Object rightValue) {
      return doBigOp(asBigInteger(leftValue), asBigInteger(rightValue));
    }
    
    private static BigInteger asBigInteger(Object value) {
      if (value instanceof BigInteger) {
        return (BigInteger)value;
      }
      if (value instanceof Integer) {
        return BigInteger.valueOf((Integer)value);
      }
      throw new RuntimeException("invalid type " + value + ' ' + value.getClass());
    }
    
    private Object doSmallOp(Object leftValue, Object rightValue) {
      int left = (Integer)leftValue;
      int right = (Integer)rightValue;
      try {
        switch(binOp) {
        case ADD:
          return ExactMath.addExact(left, right);
        case SUB:
          return ExactMath.subtractExact(left, right);
        case MUL:
          return ExactMath.multiplyExact(left, right);
        case DIV:
          return left / right;
        default:
          throw new AssertionError("unknown " + binOp);
        }
      } catch(ArithmeticException e) {
        return doBigOp(BigInteger.valueOf(left), BigInteger.valueOf(right));
      }
    }
    
    private BigInteger doBigOp(BigInteger leftValue, BigInteger rightValue) {
      switch(binOp) {
      case ADD:
        return leftValue.add(rightValue);
      case SUB:
        return leftValue.subtract(rightValue);
      case MUL:
        return leftValue.multiply(rightValue);
      case DIV:
        return leftValue.divide(rightValue);
      default:
        throw new AssertionError("unknown " + binOp);
      }
    }
  }
  
  static class TestOpNode extends Node {
    private final BinOp binOp;
    @Child
    private final Node leftNode;
    @Child
    private final Node rightNode;
    
    TestOpNode(BinOp binOp, Node leftNode, Node rightNode) {
      this.binOp = binOp;
      this.leftNode = adoptChild(leftNode);
      this.rightNode = adoptChild(rightNode);
    }

    @Override
    public Object eval(VirtualFrame frame) {
      Object leftValue = leftNode.eval(frame);
      Object rightValue = rightNode.eval(frame);
      if (leftValue instanceof Integer && rightValue instanceof Integer) {
        return doSmallOp(leftValue, rightValue);
      }
      return slowPath(leftValue, rightValue);
    }
    
    private boolean slowPath(Object leftValue, Object rightValue) {
      return doBigOp(asBigInteger(leftValue), asBigInteger(rightValue));
    }
    
    private static BigInteger asBigInteger(Object value) {
      if (value instanceof BigInteger) {
        return (BigInteger)value;
      }
      if (value instanceof Integer) {
        return BigInteger.valueOf((Integer)value);
      }
      throw new RuntimeException("invalid type " + value);
    }
    
    private boolean doSmallOp(Object leftValue, Object rightValue) {
      int left = (Integer)leftValue;
      int right = (Integer)rightValue;
      switch(binOp) {
      case LT:
        return left < right;
      case LE:
        return left <= right;
      case GT:
        return left > right;
      case GE:
        return left >= right;
      default:
        throw new AssertionError("unknown " + binOp);
      }
    }
    
    private boolean doBigOp(BigInteger leftValue, BigInteger rightValue) {
      switch(binOp) {
      case LT:
        return leftValue.compareTo(rightValue) < 0;
      case LE:
        return leftValue.compareTo(rightValue) <= 0;
      case GT:
        return leftValue.compareTo(rightValue) > 0;
      case GE:
        return leftValue.compareTo(rightValue) >= 0;
      default:
        throw new AssertionError("unknown " + binOp);
      }
    }
  }
  
  static class PrintNode extends Node {
    @Child
    private final Node node;

    PrintNode(Node node) {
      this.node = adoptChild(node);
    }
    
    @Override
    Object eval(VirtualFrame frame) {
      System.out.println(node.eval(frame));
      return List.empty();
    }
  }
  
  static class IfNode extends Node {
    @Child
    private final Node condition;
    @Child
    private final Node trueNode;
    @Child
    private final Node falseNode;
    
    IfNode(Node condition, Node trueNode, Node falseNode) {
      this.condition = adoptChild(condition);
      this.trueNode = adoptChild(trueNode);
      this.falseNode = adoptChild(falseNode);
    }
    
    @Override
    Object eval(VirtualFrame frame) {
      Object test = condition.eval(frame);
      if (!(test instanceof Boolean)) {
        throw new RuntimeException("condition value is not a boolean " + test);
      }
      if ((Boolean)test) {
        return trueNode.eval(frame);
      }
      return falseNode.eval(frame);
    }
  }
  
  static class VarLoadNode extends Node {
    private final FrameSlot slot;

    VarLoadNode(FrameSlot slot) {
      this.slot = slot;
    }
    
    @Override
    Object eval(VirtualFrame frame) {
      try {
        return frame.getObject(slot);
      } catch (FrameSlotTypeException e) {
        throw new AssertionError(e);
      }
    }
  }
  
  static class VarStoreNode extends Node {
    private final FrameSlot slot;
    @Child
    private final Node initNode;

    VarStoreNode(FrameSlot slot, Node initNode) {
      this.slot = slot;
      this.initNode = adoptChild(initNode);
    }
    
    @Override
    Object eval(VirtualFrame frame) {
      Object value = initNode.eval(frame);
      try {
        frame.setObject(slot, value);
      } catch (FrameSlotTypeException e) {
        throw new AssertionError(e);
      }
      return List.empty();
    }
  }
  
  final HashMap<Symbol,CallTarget> callTargetMap = new HashMap<>();
  
  public Interpreter() {
    // do nothing for now
  }
  
  private static void checkArguments(List list, String... descriptions) {
    Symbol symbol = (Symbol)list.get(0);
    if (list.size() != 1 + descriptions.length) {
      throw new RuntimeException("invalid number of arguments for " + symbol + ' ' + list);
    }
    
    for(int i = 0; i<descriptions.length; i++) {
      checkArgument(symbol, i, descriptions[i], list.get(i + 1));
    }
  }
  
  private static void checkArgument(Symbol symbol, int index, String description, Object value) {
    switch(description) {
    case "value":
    case "statement":
      return;
    case "symbol":
      if (!(value instanceof Symbol)) {
        throw new RuntimeException(symbol + ": invalid argument " + index +", should be a symbol, instead of " + value);
      }
      return;
    case "parameters":
      if (!(value instanceof List)) {
        throw new RuntimeException(symbol + ": invalid argument " + index +", should be a list, instead of " + value);
      }
      List parameters =(List)value;
      for(Object parameter: parameters) {
        if (!(parameter instanceof Symbol)) {
          throw new RuntimeException(symbol + ": invalid parameter name " + parameter);
        }
      }
      return;
    default:
      throw new AssertionError("unknown description " + description);
    }
  }
  
  private Node createAST(Object value, FrameDescriptor frameDescriptor) {
    if (value instanceof List) {
      return createListAST((List)value, frameDescriptor);
    }
    if (value instanceof String) {
      return createLiteralString((String)value);
    }
    if (value instanceof Symbol) {
      Symbol symbol = (Symbol)value;
      FrameSlot slot = frameDescriptor.findFrameSlot(symbol);
      if (slot == null) {  // not a local variable
        throw new RuntimeException("unknown local symbol " + symbol);
      }
      return createVarLoad(slot);
    }
    if (value instanceof Number) {
      return createLiteralNumber((Number)value);
    }
    throw new AssertionError("unknown value " + value);
  }
  
  private Node createListAST(List list, FrameDescriptor frameDescriptor) {
    if (list.isEmpty()) {
      return createLiteralList(createChildren(list, 0, frameDescriptor));
    }
    Object first = list.get(0);
    if (!(first instanceof Symbol)) {
      return createLiteralList(createChildren(list, 0, frameDescriptor));
    }
    Symbol symbol = (Symbol)first;
    switch(symbol.getName()) {
    case "def": {
      checkArguments(list, "symbol", "parameters", "statement");
      Symbol defSymbol = (Symbol)list.get(1);
      FrameDescriptor functionFrameDescriptor = new FrameDescriptor();
      List parameters = (List)list.get(2);
      FrameSlot[] slots = new FrameSlot[parameters.size()];
      for(int i = 0; i < parameters.size(); i++) {
        Symbol parameter = (Symbol) parameters.get(i);
        slots[i] = functionFrameDescriptor.addFrameSlot(parameter, FrameSlotKind.Object);
      }
      Node body = createAST(list.get(3), functionFrameDescriptor);
      NodeUtil.printTree(System.out, body);
      return createDef(defSymbol, functionFrameDescriptor, slots, body);
    }
    case "if":
      checkArguments(list, "value", "statement", "statement");
      return createIf(createAST(list.get(1), frameDescriptor),
          createAST(list.get(2), frameDescriptor),
          createAST(list.get(3), frameDescriptor));
    case "var": {
      checkArguments(list, "symbol", "value");
      Symbol varSymbol = (Symbol)list.get(1);
      FrameSlot slot = frameDescriptor.addFrameSlot(varSymbol, FrameSlotKind.Object);
      return createVarStore(slot, createAST(list.get(2), frameDescriptor));
    }
    case "print":
      checkArguments(list, "value");
      return createPrint(createAST(list.get(1), frameDescriptor));
    case "+": case "-": case "*": case "/":
    case "<": case "<=": case ">": case ">=":
      checkArguments(list, "value", "value");
      BinOp binOp = BinOp.getBinOp(symbol.getName());
      return createBinOp(binOp, createAST(list.get(1), frameDescriptor), createAST(list.get(2), frameDescriptor));
      
    default:  // variable local access or function call
      FrameSlot slot = frameDescriptor.findFrameSlot(symbol);
      if (slot == null) {
        // not a local variable so it's a method call
        return createFunCall(symbol, createChildren(list, 1, frameDescriptor));
      }
      return createLiteralList(createChildren(list, 0, frameDescriptor));
    }
  }
  
  private Node[] createChildren(List list, int offset, FrameDescriptor frameDescriptor) {
    Node[] nodes = new Node[list.size() - offset];
    for(int i=0; i<nodes.length; i++) {
      nodes[i] = createAST(list.get(i + offset), frameDescriptor);
    }
    return nodes;
  }
  
  private static Node createLiteralNumber(Number number) {
    return new ConstNode(number);
  }
  private static Node createLiteralString(String string) {
    return new ConstNode(string);
  }
  private static Node createLiteralList(Node[] nodes) {
    return new LiteralListNode(nodes);
  }
  
  private Node createDef(Symbol name, FrameDescriptor functionFrameDescriptor, FrameSlot[] slots, Node bodyNode) {
    return new DefNode(name, functionFrameDescriptor, slots, bodyNode);
  }
  private static Node createVarStore(FrameSlot slot, Node init) {
    return new VarStoreNode(slot, init);
  }
  private static Node createPrint(Node node) {
    return new PrintNode(node);
  }
  private static Node createVarLoad(FrameSlot slot) {
    return new VarLoadNode(slot);
  }
  private static Node createIf(Node condition, Node trueNode, Node falseNode) {
    return new IfNode(condition, trueNode, falseNode);
  }
  Node createFunCall(Symbol name, Node[] children) {
    return new FunCallNode(name, children);
  }
  static Node createBinOp(BinOp binOp, Node left, Node right) {
    switch(binOp) {
    case ADD: case SUB: case MUL: case DIV:
      return new NumberOpNode(binOp, left, right);
    case LT: case LE: case GT: case GE:
      return new TestOpNode(binOp, left, right);
    default:
      throw new AssertionError(binOp);
    }
  }
  
  
  public void interpret(Path path) throws IOException {
    byte[] data = Files.readAllBytes(path);
    
    TruffleRuntime runtime = Truffle.getRuntime();
    System.out.println("using " + runtime.getName());
    
    Parser parser = new Parser(data);
    while(!parser.end()) {
      List list = parser.parseList();
      
      Node node = createAST(list, new FrameDescriptor());
      EvalNode evalNode = new EvalNode(node);
      CallTarget callTarget = runtime.createCallTarget(evalNode);
      callTarget.call();
    }
  }
  
}
