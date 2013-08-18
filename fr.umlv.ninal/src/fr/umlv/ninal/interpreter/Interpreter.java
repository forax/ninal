package fr.umlv.ninal.interpreter;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import com.oracle.truffle.api.Arguments;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.SlowPath;
import com.oracle.truffle.api.ExactMath;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleRuntime;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.FrameUtil;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.InvalidAssumptionException;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

import fr.umlv.ninal.lang.List;
import fr.umlv.ninal.lang.Symbol;
import fr.umlv.ninal.parser.Parser;

public class Interpreter {
  static final int TYPE_STATE_UNINITIALIZED = 1;
  static final int TYPE_STATE_BOOLEAN = 2;
  static final int TYPE_STATE_INT = 3;
  static final int TYPE_STATE_OBJECT = 4;
  
  static boolean isTypeState(Assumption typeStateStable, int typeState, int state) {
    try {
      typeStateStable.check();
      return typeState == state;
    } catch (InvalidAssumptionException e) {
      System.out.println("assumption invalidation !");
      return typeState == state;
    }
  }
  
  static abstract class Node extends com.oracle.truffle.api.nodes.Node {
    @CompilationFinal
    private int typeState;
    @CompilationFinal
    private Assumption typeStateStable; 
    
    protected Node(int typeState) {
      CompilerDirectives.transferToInterpreter();
      this.typeState = typeState;
      typeStateStable = Truffle.getRuntime().createAssumption();
    }
    
    final boolean isTypeState(int state) {
      return Interpreter.isTypeState(typeStateStable, typeState, state);
    }
    
    final void setTypeState(int state) {
      if (Interpreter.isTypeState(typeStateStable, typeState, state)) {
        return;
      }
      CompilerDirectives.transferToInterpreter();
      typeState = state;
      typeStateStable.invalidate();
      typeStateStable = Truffle.getRuntime().createAssumption();
      System.out.println(this  + " -> " + state);
    }
    
    Object eval(VirtualFrame frame) {
      if (isTypeState(TYPE_STATE_UNINITIALIZED)) {
        Object result = evalObject(frame);
        setTypeState(typeStateForResult(result));
        return result;
      }
      if (isTypeState(TYPE_STATE_INT)) {
        try {
          return evalInt(frame);
        } catch(UnexpectedResultException e) {
          setTypeState(TYPE_STATE_OBJECT);
          return e.getResult();
        }
      }
      if (isTypeState(TYPE_STATE_BOOLEAN)) {
        try {
          return evalBoolean(frame);
        } catch(UnexpectedResultException e) {
          setTypeState(TYPE_STATE_OBJECT);
          return e.getResult();
        }
      }
      return evalObject(frame);
    }
    
    /** Generic version of eval
     * @param frame current stack frame
     */
    @SuppressWarnings("static-method")
    Object evalObject(VirtualFrame frame) {
      throw invalidType();
    }
    
    /** Specialized version of eval for int return value
     * @param frame current stack frame
     * @throws UnexpectedResultException if result is not an int.
     */
    @SuppressWarnings("static-method")
    int evalInt(VirtualFrame frame) throws UnexpectedResultException {
      throw invalidType();
    }
     
    /** Specialized version of eval for boolean return value
     * @param frame current stack frame
     * @throws UnexpectedResultException if result is not a boolean.
     */
    @SuppressWarnings("static-method")
    boolean evalBoolean(VirtualFrame frame) throws UnexpectedResultException {
      throw invalidType();
    }
  }
  
  static class ConstNode extends Node {
    private final Object constant;
    
    ConstNode(Object constant) {
      super((constant instanceof Integer)? TYPE_STATE_INT: TYPE_STATE_OBJECT);
      assert !(constant instanceof Boolean);
      this.constant = constant;
    }

    @Override
    Object evalObject(VirtualFrame frame) {
      return constant;
    }
    @Override
    int evalInt(VirtualFrame frame) {
      return (Integer)constant;
    }
  }
  
  static class LiteralListNode extends Node {
    @Children
    private final Node[] valueNodes;
    
    LiteralListNode(Node[] nodes) {
      super(TYPE_STATE_OBJECT);
      this.valueNodes = adoptChildren(nodes);
    }

    @Override
    @ExplodeLoop
    Object evalObject(VirtualFrame frame) {
      Object[] values = new Object[valueNodes.length];
      for(int i=0; i<values.length; i++) {
        values[i] = valueNodes[i].evalObject(frame);
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
    
    // no type specialization, EvalNode.execute is called once !
  }
  
  static class FunctionNode extends com.oracle.truffle.api.nodes.RootNode {
    @SuppressWarnings("unused")   // used for debugging when printing the node tree
    private final Symbol symbol;
    @Children
    private final ParameterNode[] parameterNodes;
    @Child
    private final Node bodyNode;
    
    FunctionNode(Symbol symbol, ParameterNode[] parameterNodes, Node bodyNode) {
      this.symbol = symbol;
      this.parameterNodes = adoptChildren(parameterNodes);
      this.bodyNode = adoptChild(bodyNode);
    }
    
    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
      ArrayArguments arguments = frame.getArguments(ArrayArguments.class);
      if (parameterNodes.length != arguments.size()) {
        throw invalidNumberOfArgument();
      }
      
      for(int i = 0; i < parameterNodes.length; i++) {
        parameterNodes[i].setObject(frame, arguments.get(i));
      }
      return bodyNode.eval(frame);
    }
  }
  
  
  
  /*non-static*/ class DefNode extends Node {
    private final Symbol name;
    private final FrameDescriptor functionFrameDescriptor;
    @Children
    private final ParameterNode[] parameterNodes;
    @Child
    private final Node bodyNode;
    
    DefNode(Symbol name, FrameDescriptor functionFrameDescriptor, ParameterNode[] parameterNodes, Node bodyNode) {
      super(TYPE_STATE_OBJECT);
      this.name = name;
      this.functionFrameDescriptor = functionFrameDescriptor;
      this.parameterNodes = adoptChildren(parameterNodes);
      this.bodyNode = adoptChild(bodyNode);
    }

    public Symbol getName() {
      return name;
    }
    
    @Override
    Object evalObject(VirtualFrame frame) {
      FunctionNode functionNode = new FunctionNode(name, parameterNodes, bodyNode);
      NodeUtil.printTree(System.out, functionNode);
      CallTarget callTarget = Truffle.getRuntime().createCallTarget(functionNode, functionFrameDescriptor);
      callTargetMap.put(name, callTarget);
      
      return List.empty();
    }
  }
  
  /*non-static*/ class FunCallNode extends Node {
    private final Symbol name;
    @Children
    private final Node[] argumentNodes;
    
    @CompilationFinal
    private CallTarget callTarget;
    
    FunCallNode(Symbol name, Node[] argumentNodes) {
      super(TYPE_STATE_OBJECT);
      this.name = name;
      this.argumentNodes = adoptChildren(argumentNodes);
      
      // try to early bind if possible
      this.callTarget = callTargetMap.get(name);
    }

    @Override
    @ExplodeLoop
    Object evalObject(VirtualFrame frame) {
      Object[] arguments = new Object[argumentNodes.length];
      for(int i=0; i<argumentNodes.length; i++) {
        arguments[i] = argumentNodes[i].eval(frame);
      }
      
      //FIXME stupid ahead of time compilation
      CallTarget callTarget = this.callTarget;
      if (callTarget != null) {
        return callTarget.call(frame.pack(), new ArrayArguments(arguments));
      }
      
      callTarget = callTargetMap.get(name);
      this.callTarget = callTarget;
      return callTarget.call(frame.pack(), new ArrayArguments(arguments));
    }
    
    @Override
    int evalInt(VirtualFrame frame) throws UnexpectedResultException {
      Object result = evalObject(frame);
      if (result instanceof Integer) {
        return (Integer)result;
      }
      throw new UnexpectedResultException(result);
    }
    
    @Override
    boolean evalBoolean(VirtualFrame frame) throws UnexpectedResultException {
      Object result = evalObject(frame);
      if (result instanceof Boolean) {
        return (Boolean)result;
      }
      throw new UnexpectedResultException(result);
    }
  }
  
  static class ParameterNode extends com.oracle.truffle.api.nodes.Node {
    private final FrameSlot slot;
    @CompilationFinal
    private int typeState;
    @CompilationFinal
    private Assumption typeStateStable; 

    ParameterNode(FrameSlot slot) {
      this.slot = slot;
      CompilerDirectives.transferToInterpreter();
      typeState = TYPE_STATE_UNINITIALIZED;
      typeStateStable = Truffle.getRuntime().createAssumption();
    }
    
    private void setTypeState(int state) {
      if (Interpreter.isTypeState(typeStateStable, typeState, state)) {
        return;
      }
      CompilerDirectives.transferToInterpreter();
      typeState = state;
      typeStateStable = Truffle.getRuntime().createAssumption();
      System.out.println(this  + " -> " + state);
    }
    
    private boolean isTypeState(int state) {
      return Interpreter.isTypeState(typeStateStable, typeState, state);
    }
    
    void setObject(VirtualFrame frame, Object value) {
      if (isTypeState(TYPE_STATE_UNINITIALIZED)) {
        if (value instanceof Integer) {
          FrameUtil.setIntSafe(frame, slot, (Integer)value);
          setTypeState(TYPE_STATE_INT);
          return;
        }
        if (value instanceof Boolean) {
          FrameUtil.setBooleanSafe(frame, slot, (Boolean)value);
          setTypeState(TYPE_STATE_BOOLEAN);
          return;
        }
        FrameUtil.setObjectSafe(frame, slot, value);
        setTypeState(TYPE_STATE_OBJECT);
        return;
      }
      if (isTypeState(TYPE_STATE_INT)) {
        if (value instanceof Integer) {
          try {
            frame.setInt(slot, (Integer)value);
          } catch (FrameSlotTypeException e) {
            FrameUtil.setObjectSafe(frame, slot, value);
            setTypeState(TYPE_STATE_OBJECT);  
          }
          return;
        }
      }
      if (isTypeState(TYPE_STATE_BOOLEAN)) {
        if (value instanceof Boolean) {
          try {
            frame.setBoolean(slot, (Boolean)value);
          } catch (FrameSlotTypeException e) {
            FrameUtil.setObjectSafe(frame, slot, value);
            setTypeState(TYPE_STATE_OBJECT);  
          }
          return;
        }
      }
      try {
        frame.setObject(slot, value);
      } catch (FrameSlotTypeException e) {
        FrameUtil.setObjectSafe(frame, slot, value);
        setTypeState(TYPE_STATE_OBJECT);  
      }
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
  
  static class BlockNode extends Node {
    @Children
    private final Node[] nodes;
    
    BlockNode(Node[] nodes) {
      super(TYPE_STATE_UNINITIALIZED);
      this.nodes = adoptChildren(nodes);
    }

    @Override
    @ExplodeLoop
    Object evalObject(VirtualFrame frame) {
      Object result = List.empty();
      for(int i = 0; i < nodes.length; i++) {
        result = nodes[i].eval(frame);
      }
      return result;
    }
    
    @Override
    boolean evalBoolean(VirtualFrame frame) throws UnexpectedResultException {
      if (nodes.length == 0) {
        setTypeState(TYPE_STATE_OBJECT);
        throw new UnexpectedResultException(List.empty());
      }
      for(int i = 0; i < nodes.length - 1; i++) {
        nodes[i].eval(frame);
      }
      Node last = nodes[nodes.length - 1];
      try {
        return last.evalBoolean(frame);
      } catch(UnexpectedResultException e) {
        setTypeState(TYPE_STATE_OBJECT);
        throw e;
      }
    }
    
    @Override
    int evalInt(VirtualFrame frame) throws UnexpectedResultException {
      if (nodes.length == 0) {
        setTypeState(TYPE_STATE_OBJECT);
        throw new UnexpectedResultException(List.empty());
      }
      for(int i = 0; i < nodes.length - 1; i++) {
        nodes[i].eval(frame);
      }
      Node last = nodes[nodes.length - 1];
      try {
        return last.evalInt(frame);
      } catch(UnexpectedResultException e) {
        setTypeState(TYPE_STATE_OBJECT);
        throw e;
      }
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
      super(TYPE_STATE_INT);
      this.binOp = binOp;
      this.leftNode = adoptChild(leftNode);
      this.rightNode = adoptChild(rightNode);
    }

    @Override
    Object evalObject(VirtualFrame frame) {
      Object leftValue = leftNode.evalObject(frame);
      Object rightValue = rightNode.evalObject(frame);
      if (leftValue instanceof Integer && rightValue instanceof Integer) {
        int left = (Integer)leftValue;
        int right = (Integer)rightValue;
        try {
          return doSmallOp(left, right);
        } catch (UnexpectedResultException e) {
          return e.getResult();
        }
      }
      return slowPath(leftValue, rightValue);
    }
    @SlowPath
    private BigInteger slowPath(Object leftValue, Object rightValue) {
      return doBigOp(asBigInteger(leftValue), asBigInteger(rightValue));
    }
    
    @Override
    int evalInt(VirtualFrame frame) throws UnexpectedResultException {
      Object leftValue;
      int left;
      try {
        left = leftNode.evalInt(frame);
        leftValue = null;
      } catch(UnexpectedResultException e) {
        left = 0;
        leftValue = e.getResult();
      }
      Object rightValue;
      int right;
      try {
        right = rightNode.evalInt(frame);
        rightValue = null;
      } catch(UnexpectedResultException e) {
        right = 0;
        rightValue = e.getResult();
      }
      if (leftValue == null && rightValue == null) {
        try {
          return doSmallOp(left, right);
        } catch(UnexpectedResultException e) {
          setTypeState(TYPE_STATE_OBJECT);
          throw e;
        }
      }
      throw slowPathInt(leftValue, left, rightValue, right);
    }
    @SlowPath
    private UnexpectedResultException slowPathInt(Object leftValue, int left, Object rightValue, int right) throws UnexpectedResultException {
      setTypeState(TYPE_STATE_OBJECT);
      if (leftValue == null) {
        leftValue = left;
      }
      if (rightValue == null) {
        rightValue = right;
      }
      throw new UnexpectedResultException(slowPath(leftValue, rightValue));
    }
    
    private int doSmallOp(int left, int right) throws UnexpectedResultException {
      try {
        if (binOp == BinOp.ADD) {
          return ExactMath.addExact(left, right);
        }
        if (binOp == BinOp.SUB) {
          return ExactMath.subtractExact(left, right);
        }
        if (binOp == BinOp.MUL) {
          return ExactMath.multiplyExact(left, right);
        }
        if (binOp == BinOp.DIV) {
          return left / right;
        }
        throw should_not_reach_here();
      } catch(ArithmeticException e) {
        throw new UnexpectedResultException(slowPath(left, right));
      }
    }
    
    @SlowPath
    private BigInteger slowPath(int leftValue, int rightValue) {
      return doBigOp(BigInteger.valueOf(leftValue), BigInteger.valueOf(rightValue));
    }
    
    private BigInteger doBigOp(BigInteger leftValue, BigInteger rightValue) {
      if (binOp == BinOp.ADD) {
        return leftValue.add(rightValue);
      }
      if (binOp == BinOp.SUB) {
        return leftValue.subtract(rightValue);
      }
      if (binOp == BinOp.SUB) {
        return leftValue.multiply(rightValue);
      }
      if (binOp == BinOp.SUB) {
        return leftValue.divide(rightValue);
      }
      throw should_not_reach_here();
    }
    
    @Override
    boolean evalBoolean(VirtualFrame frame) {
      throw invalidType();
    }
  }
  
  static class TestOpNode extends Node {
    private final BinOp binOp;
    @Child
    private final Node leftNode;
    @Child
    private final Node rightNode;
    
    TestOpNode(BinOp binOp, Node leftNode, Node rightNode) {
      super(TYPE_STATE_BOOLEAN);
      this.binOp = binOp;
      this.leftNode = adoptChild(leftNode);
      this.rightNode = adoptChild(rightNode);
    }

    @Override
    Object evalObject(VirtualFrame frame) {
       return evalBooleanGeneric(frame);
    }
    private boolean evalBooleanGeneric(VirtualFrame frame) {
      Object leftValue = leftNode.evalObject(frame);
      Object rightValue = rightNode.evalObject(frame);
      if (leftValue instanceof Integer && rightValue instanceof Integer) {
        int left = (Integer)leftValue;
        int right = (Integer)rightValue;
        return doSmallOp(left, right);
      }
      return slowPath(leftValue, rightValue);
    }
    @SlowPath
    private boolean slowPath(Object leftValue, Object rightValue) {
      return doBigOp(asBigInteger(leftValue), asBigInteger(rightValue));
    }
    
    @Override
    boolean evalBoolean(VirtualFrame frame) {
      if (!leftNode.isTypeState(TYPE_STATE_INT) || !rightNode.isTypeState(TYPE_STATE_INT)) {
        return evalBooleanGeneric(frame);
      }
      Object leftValue;
      int left;
      try {
        left = leftNode.evalInt(frame);
        leftValue = null;
      } catch(UnexpectedResultException e) {
        left = 0;
        leftValue = e.getResult();
      }
      Object rightValue;
      int right;
      try {
        right = rightNode.evalInt(frame);
        rightValue = null;
      } catch(UnexpectedResultException e) {
        right = 0;
        rightValue = e.getResult();
      }
      if (leftValue == null && rightValue == null) {
        return doSmallOp(left, right);
      }
      return slowPathInt(leftValue, left, rightValue, right);
    }
    @SlowPath
    private boolean slowPathInt(Object leftValue, int left, Object rightValue, int right) {
      if (leftValue == null) {
        leftValue = left;
      }
      if (rightValue == null) {
        rightValue = right;
      }
      return slowPath(leftValue, rightValue);
    }
    
    
    private boolean doSmallOp(int left, int right) {
      if (binOp == BinOp.LT) {
        return left < right;
      }
      if (binOp == BinOp.LE) {
        return left <= right;
      }
      if (binOp == BinOp.GT) {
        return left > right;
      }
      if (binOp == BinOp.GE) {
        return left >= right;
      }
      throw should_not_reach_here();
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
        throw should_not_reach_here();
      }
    }
  }
  
  static class PrintNode extends Node {
    @Child
    private final Node node;

    PrintNode(Node node) {
      super(TYPE_STATE_OBJECT);
      this.node = adoptChild(node);
    }
    
    @Override
    Object evalObject(VirtualFrame frame) {
      try {
        if (node.isTypeState(TYPE_STATE_BOOLEAN)) {
          System.out.println(node.evalBoolean(frame));
        }
        if (node.isTypeState(TYPE_STATE_INT)) {
          System.out.println(node.evalInt(frame));
        }
      } catch(UnexpectedResultException e) {
        System.out.println(e.getResult());
      }
      System.out.println(node.evalObject(frame));
      return List.empty();
    }
    
    @Override
    boolean evalBoolean(VirtualFrame frame) {
      throw invalidType();
    }
    @Override
    int evalInt(VirtualFrame frame) {
      throw invalidType();
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
      super(TYPE_STATE_UNINITIALIZED);
      this.condition = adoptChild(condition);
      this.trueNode = adoptChild(trueNode);
      this.falseNode = adoptChild(falseNode);
    }
    
    @Override
    Object evalObject(VirtualFrame frame) {
      boolean test;
      try {
        test = condition.evalBoolean(frame);
      } catch (UnexpectedResultException e) {
        throw conditionIsNotABoolean();
      }
      if (test) {
        return trueNode.evalObject(frame);
      }
      return falseNode.evalObject(frame);
    }
    
    @Override
    boolean evalBoolean(VirtualFrame frame) throws UnexpectedResultException {
      boolean test;
      try {
        test = condition.evalBoolean(frame);
      } catch (UnexpectedResultException e) {
        throw conditionIsNotABoolean();
      }
      try {
        if (test) {
          return trueNode.evalBoolean(frame);
        }
        return falseNode.evalBoolean(frame);
      } catch(UnexpectedResultException e) {
        setTypeState(TYPE_STATE_OBJECT);
        throw e;
      }
    }
    
    @Override
    int evalInt(VirtualFrame frame) throws UnexpectedResultException {
      boolean test;
      try {
        test = condition.evalBoolean(frame);
      } catch (UnexpectedResultException e) {
        throw conditionIsNotABoolean();
      }
      try {
        if (test) {
          return trueNode.evalInt(frame);
        }
        return falseNode.evalInt(frame);
      } catch(UnexpectedResultException e) {
        setTypeState(TYPE_STATE_OBJECT);
        throw e;
      }
    }
  }
  
  static class RangeNode extends Node {
    private final FrameSlot slot;
    @Child
    private final Node firstNode;
    @Child
    private final Node lastNode;
    @Child
    private final Node bodyNode;
    
    RangeNode(FrameSlot slot, Node firstNode, Node lastNode, Node bodyNode) {
      super(TYPE_STATE_UNINITIALIZED);
      this.slot = slot;
      this.firstNode = adoptChild(firstNode);
      this.lastNode = adoptChild(lastNode);
      this.bodyNode = adoptChild(bodyNode);
    }

    @Override
    @ExplodeLoop   // if possible
    Object evalObject(VirtualFrame frame) {
      int first;
      try {
        first = firstNode.evalInt(frame);
      } catch (UnexpectedResultException e) {
        throw rangeInitialValueMustBeAnInteger();
      }
      int last;
      try {
        last = lastNode.evalInt(frame);
      } catch (UnexpectedResultException e) {
        throw rangeLastValueMustBeAnInteger();
      }
      
      Object result = List.empty();
      FrameSlot slot = this.slot;
      for(int i = first; i < last; i++) {
        FrameUtil.setIntSafe(frame, slot, i);
        bodyNode.eval(frame);
      }
      return result;
    }
    
    @Override
    @ExplodeLoop   // if possible
    boolean evalBoolean(VirtualFrame frame) throws UnexpectedResultException {
      int first;
      try {
        first = firstNode.evalInt(frame);
      } catch (UnexpectedResultException e) {
        throw rangeInitialValueMustBeAnInteger();
      }
      int last;
      try {
        last = lastNode.evalInt(frame);
      } catch (UnexpectedResultException e) {
        throw rangeLastValueMustBeAnInteger();
      }
      
      if (first >= last) {
        setTypeState(TYPE_STATE_OBJECT);
        throw new UnexpectedResultException(List.empty());
      }
      
      FrameSlot slot = this.slot;
      for(int i = first; i < last - 1; i++) {
        FrameUtil.setIntSafe(frame, slot, i);
        bodyNode.eval(frame);
      }
      FrameUtil.setIntSafe(frame, slot, last - 1);
      try {
        return bodyNode.evalBoolean(frame);
      } catch(UnexpectedResultException e) {
        setTypeState(TYPE_STATE_OBJECT);
        throw e;
      }
    }
    
    @Override
    @ExplodeLoop   // if possible
    int evalInt(VirtualFrame frame) throws UnexpectedResultException {
      int first;
      try {
        first = firstNode.evalInt(frame);
      } catch (UnexpectedResultException e) {
        throw rangeInitialValueMustBeAnInteger();
      }
      int last;
      try {
        last = lastNode.evalInt(frame);
      } catch (UnexpectedResultException e) {
        throw rangeLastValueMustBeAnInteger();
      }
      
      if (first >= last) {
        setTypeState(TYPE_STATE_OBJECT);
        throw new UnexpectedResultException(List.empty());
      }
      
      FrameSlot slot = this.slot;
      for(int i = first; i < last - 1; i++) {
        FrameUtil.setIntSafe(frame, slot, i);
        bodyNode.eval(frame);
      }
      FrameUtil.setIntSafe(frame, slot, last - 1);
      try {
        return bodyNode.evalInt(frame);
      } catch(UnexpectedResultException e) {
        setTypeState(TYPE_STATE_OBJECT);
        throw e;
      }
    }
  }
  
  static class VarLoadNode extends Node {
    private final FrameSlot slot;

    VarLoadNode(FrameSlot slot) {
      super(typeStateForFrameSlotkind(slot.getKind()));
      this.slot = slot;
    }
    
    static int typeStateForFrameSlotkind(FrameSlotKind kind) {
      switch(kind) {
      case Boolean:
        return TYPE_STATE_BOOLEAN;
      case Int:
        return TYPE_STATE_INT;
      default:
        return TYPE_STATE_UNINITIALIZED;
      }
    }
    
    @Override
    Object evalObject(VirtualFrame frame) {
      if (isTypeState(TYPE_STATE_UNINITIALIZED)) {
        return frame.getValue(slot);
      }
      
      try {
        return frame.getObject(slot);
      } catch (FrameSlotTypeException e) {
        throw should_not_reach_here();
      }
    }
    
    @Override
    boolean evalBoolean(VirtualFrame frame) throws UnexpectedResultException {
      try {
        return frame.getBoolean(slot);
      } catch (FrameSlotTypeException e) {
        setTypeState(TYPE_STATE_OBJECT);
        throw new UnexpectedResultException(frame.getValue(slot));
      }
    }
    
    @Override
    int evalInt(VirtualFrame frame) throws UnexpectedResultException {
      try {
        return frame.getInt(slot);
      } catch (FrameSlotTypeException e) {
        setTypeState(TYPE_STATE_OBJECT);
        throw new UnexpectedResultException(frame.getValue(slot));
      }
    }
  }
  
  static class VarStoreNode extends Node {
    private final FrameSlot slot;
    @Child
    private final Node initNode;

    VarStoreNode(FrameSlot slot, Node initNode) {
      super(TYPE_STATE_UNINITIALIZED);
      this.slot = slot;
      this.initNode = adoptChild(initNode);
    }
    
    @Override
    Object eval(VirtualFrame frame) {
      if (isTypeState(TYPE_STATE_UNINITIALIZED)) {
        Object value = initNode.eval(frame);
        if (value instanceof Integer) {
          FrameUtil.setIntSafe(frame, slot, (Integer)value);
        }
        else if (value instanceof Boolean) {
          FrameUtil.setBooleanSafe(frame, slot, (Boolean)value);
        } else {
          FrameUtil.setObjectSafe(frame, slot, value);
        }
        setTypeState(TYPE_STATE_OBJECT);
        return List.empty();
      }
      if (initNode.isTypeState(TYPE_STATE_INT)) {
        int value;
        try {
          value = initNode.evalInt(frame);
        } catch (UnexpectedResultException e) {
          FrameUtil.setObjectSafe(frame, slot, e.getResult());
          return List.empty();
        }
        try {
          frame.setInt(slot, value);
        } catch (FrameSlotTypeException e) {
          FrameUtil.setObjectSafe(frame, slot, value);
          return List.empty();
        }
        return List.empty();
      }
      if (initNode.isTypeState(TYPE_STATE_BOOLEAN)) {
        boolean value;
        try {
          value = initNode.evalBoolean(frame);
        } catch (UnexpectedResultException e) {
          FrameUtil.setObjectSafe(frame, slot, e.getResult());
          return List.empty();
        }
        try {
          frame.setBoolean(slot, value);
        } catch (FrameSlotTypeException e) {
          FrameUtil.setObjectSafe(frame, slot, value);
          return List.empty();
        }
        return List.empty();
      }
      Object value = initNode.eval(frame);
      try {
        frame.setObject(slot, value);
      } catch (FrameSlotTypeException e) {
        FrameUtil.setObjectSafe(frame, slot, value);
        return List.empty();
      }
      return List.empty();
    }
  }
  
  static int typeStateForResult(Object result) {
    if (result instanceof Integer) {
      return TYPE_STATE_INT;
    }
    if (result instanceof Boolean) {
      return TYPE_STATE_BOOLEAN;
    }
    return TYPE_STATE_OBJECT;
  }
  
  static BigInteger asBigInteger(Object value) {
    if (value instanceof BigInteger) {
      return (BigInteger)value;
    }
    if (value instanceof Integer) {
      return BigInteger.valueOf((Integer)value);
    }
    throw invalidType();
  }
  
  
  @SlowPath static Error should_not_reach_here() {
    throw new AssertionError();
  }
  
  @SlowPath static RuntimeException invalidNumberOfArgument() {
    throw new RuntimeException("invalid number of arguments for function call");
  }
  @SlowPath static RuntimeException conditionIsNotABoolean() {
    throw new RuntimeException("condition value is not a boolean");
  }
  @SlowPath static RuntimeException rangeInitialValueMustBeAnInteger() {
    throw new RuntimeException("range initial value must be an integer");
  }
  @SlowPath static RuntimeException rangeLastValueMustBeAnInteger() {
    throw new RuntimeException("range last value must be an integer");
  }
  @SlowPath static RuntimeException invalidType() {
    throw new RuntimeException("invalid type");
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
      System.out.println(frameDescriptor.getSlots());
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
      ParameterNode[] parameterNodes = new ParameterNode[parameters.size()];
      for(int i = 0; i < parameters.size(); i++) {
        Symbol parameter = (Symbol) parameters.get(i);
        parameterNodes[i] = new ParameterNode(functionFrameDescriptor.addFrameSlot(parameter, FrameSlotKind.Object));
      }
      Node body = createAST(list.get(3), functionFrameDescriptor);
      return createDef(defSymbol, functionFrameDescriptor, parameterNodes, body);
    }
    case "block": {
      Node[] nodes = new Node[list.size() - 1];
      for(int i = 0; i < nodes.length; i++) {
        nodes[i] = createAST(list.get(i + 1), frameDescriptor);
      }
      return createBlock(nodes);
    }
    case "if":
      checkArguments(list, "value", "statement", "statement");
      return createIf(createAST(list.get(1), frameDescriptor),
          createAST(list.get(2), frameDescriptor),
          createAST(list.get(3), frameDescriptor));
    case "range": {
      checkArguments(list, "symbol", "value", "value", "value");
      Symbol local = (Symbol)list.get(1);
      Node firstNode = createAST(list.get(2), frameDescriptor);
      Node lastNode = createAST(list.get(3), frameDescriptor);
      FrameSlot slot = frameDescriptor.addFrameSlot(local, FrameSlotKind.Int);
      return createRange(slot, firstNode, lastNode,
          createAST(list.get(4), frameDescriptor));
    }
    case "var": {
      checkArguments(list, "symbol", "value");
      Symbol varSymbol = (Symbol)list.get(1);
      FrameSlot slot = frameDescriptor.addFrameSlot(varSymbol, FrameSlotKind.Object);
      return createVarStore(slot, createAST(list.get(2), frameDescriptor));
    }
    case "set": {
      checkArguments(list, "symbol", "value");
      Symbol varSymbol = (Symbol)list.get(1);
      FrameSlot slot = frameDescriptor.findFrameSlot(varSymbol);
      if (slot == null) {
        throw new RuntimeException("unknown local symbol " + varSymbol);
      }
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
  
  private Node createDef(Symbol name, FrameDescriptor functionFrameDescriptor, ParameterNode[] parameterNodes, Node bodyNode) {
    return new DefNode(name, functionFrameDescriptor, parameterNodes, bodyNode);
  }
  private static Node createBlock(Node[] nodes) {
    return new BlockNode(nodes);
  }
  private static Node createVarStore(FrameSlot slot, Node init) {
    return new VarStoreNode(slot, init);
  }
  private static Node createVarLoad(FrameSlot slot) {
    return new VarLoadNode(slot);
  }
  private static Node createIf(Node condition, Node trueNode, Node falseNode) {
    return new IfNode(condition, trueNode, falseNode);
  }
  private static Node createRange(FrameSlot slot, Node firstNode, Node lastNode, Node bodyNode) {
    return new RangeNode(slot, firstNode, lastNode, bodyNode);
  }
  private static Node createPrint(Node node) {
    return new PrintNode(node);
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
      
      FrameDescriptor frameDescriptor = new FrameDescriptor();
      Node node = createAST(list, frameDescriptor);
      EvalNode evalNode = new EvalNode(node);
      CallTarget callTarget = runtime.createCallTarget(evalNode, frameDescriptor);
      callTarget.call();
    }
  }
  
}
