package ppl.delite.framework.datastructures

import java.io.{File,FileWriter,PrintWriter}
import scala.reflect.{RefinedManifest, SourceContext}
import scala.virtualization.lms.common._
import scala.virtualization.lms.internal.{CudaCodegen,OpenCLCodegen,CCodegen,CLikeCodegen,GenerationFailedException}
import scala.virtualization.lms.internal.Targets._
import ppl.delite.framework.ops.DeliteOpsExp
import ppl.delite.framework.Config
import ppl.delite.framework.Util._
import scala.reflect.SourceContext
import scala.collection.mutable.HashSet

trait DeliteStructsExp extends StructExp { this: DeliteOpsExp with PrimitiveOpsExp with OrderingOpsExp => // FIXME: mix in prim somewhere else
	
  abstract class DeliteStruct[T:Manifest] extends AbstractStruct[T] with DeliteOp[T] {
    type OpType <: DeliteStruct[T]
    val tag = classTag[T]

    def copyTransformedElems(e: => Seq[(String, Rep[Any])]): Seq[(String, Rep[Any])] = 
      original.map(p=>(p._2.asInstanceOf[OpType]).elems.map(e=>(e._1,p._1(e._2)))).getOrElse(e)
  }

  //the following is a HACK to make struct inputs appear in delite op kernel input lists while keeping them out of the read effects list
  //the proper solution is to simply override readSyms as done in trait StructExp, but see def freeInScope in BlockTraversal.scala
  override def readSyms(e: Any): List[Sym[Any]] = e match {
    case s: AbstractStruct[_] => s.elems.flatMap(e => readSyms(e._2)).toList
    case _ => super.readSyms(e)
  }

  override def reflectEffect[A:Manifest](d: Def[A], u: Summary)(implicit pos: SourceContext): Exp[A] =  d match {
    case s: AbstractStruct[_] => reflectEffectInternal(d, u)
    case _ => super.reflectEffect(d,u)
  }

  case class NestedFieldUpdate[T:Manifest](struct: Exp[Any], fields: List[String], rhs: Exp[T]) extends Def[Unit]

  override def field_update[T:Manifest](struct: Exp[Any], index: String, rhs: Exp[T]) = recurseFields(struct, List(index), rhs)

  //no shortcutting on mutable structs ...

  // TODO: clean up and check everything's safe
  override def field[T:Manifest](struct: Exp[Any], index: String)(implicit pos: SourceContext): Exp[T] = struct match {
    // is this confined to unsafe immutable or should we look at any mutable struct def?
    /*
    case Def(rhs@Reflect(ObjectUnsafeImmutable(orig), u, es)) => 
      println("**** trying to shortcut field access: " + struct.toString + "=" + rhs + "." + index)

      for (e@Def(r) <- es) {
        println("      dep: " + e.toString + "=" + r)
      }

      // find last assignment ... FIXME: should look at *all* mutations of orig
      val writes = es collect {
        case Def(Reflect(NestedFieldUpdate(`orig`,List(`index`),rhs), _, _)) => rhs
      }
      writes.reverse match {
        case rhs::_ => 
          println("      picking write " + rhs.toString)
          rhs.asInstanceOf[Exp[T]] // take last one
        case Nil => 
          orig match {
            case Def(Reflect(SimpleStruct(tag, fields), _, _)) =>
              val Def(Reflect(NewVar(rhs), _,_)) = fields.find(_._1 == index).get._2
              println("      picking alloc " + rhs.toString) 
              rhs.asInstanceOf[Exp[T]] // take field
            case _ =>
              println("      giving up...")
              super.field(struct, index)
          }
      }
    */
    /*
    case Def(rhs@Reflect(SimpleStruct(tag, fields), _, _)) =>
      println("**** trying to shortcut field access: " + struct.toString + "=" + rhs + "." + index)

      // find last assignment ... FIXME: should look at *all* mutations of struct
      /*context foreach {
        case Def(Reflect(NestedFieldUpdate(`struct`,List(`index`),rhs), _, _)) =>  //ok
        case Def(e) => 
          println("      ignoring " + e)
      }*/
      val writes = context collect {
        case Def(Reflect(NestedFieldUpdate(`struct`,List(`index`),rhs), _, _)) => rhs
      }
      writes.reverse match {
        case rhs::_ => 
          println("      picking write " + rhs.toString)
          rhs.asInstanceOf[Exp[T]] // take last one
        case Nil =>
          val Def(Reflect(NewVar(rhs), _,_)) = fields.find(_._1 == index).get._2
          println("      picking alloc " + rhs.toString)
          rhs.asInstanceOf[Exp[T]] // take field
      }
      */
    case _ => super.field(struct, index)
  }


  private def recurseFields[T:Manifest](struct: Exp[Any], fields: List[String], rhs: Exp[T]): Exp[Unit] = struct match {
    case Def(Reflect(Field(s,name),_,_)) => recurseFields(s, name :: fields, rhs)
    case _ => reflectWrite(struct)(NestedFieldUpdate(struct, fields, rhs))
  }

  // TODO: get rid of entirely or just use mirrorDef
  def mirrorDD[A:Manifest](e: Def[A], f: Transformer)(implicit ctx: SourceContext): Def[A] = (e match {
    case IntTimes(a,b) => 
      printlog("warning: encountered effectful primitive def during mirror "+e)
      IntTimes(f(a),f(b))
    case IntPlus(a,b) => 
      printlog("warning: encountered effectful primitive def during mirror "+e)
      IntPlus(f(a),f(b))
    case IntMinus(a,b) => 
      printlog("warning: encountered effectful primitive def during mirror "+e)
      IntMinus(f(a),f(b))
    case IntMod(a,b) => 
      printlog("warning: encountered effectful primitive def during mirror "+e)
      IntMod(f(a),f(b))
    case IntDivide(a,b) => 
      printlog("warning: encountered effectful primitive def during mirror "+e)
      IntDivide(f(a),f(b)) //xx
    case e@OrderingLT(a,b) =>
      printlog("warning: encountered effectful primitive def during mirror "+e)
      OrderingLT(f(a),f(b))(null.asInstanceOf[Ordering[Any]],manifest[Any]) //HACK
    case e@Reflect(a,u,es) => Reflect(mirrorDD(a,f),mapOver(f,u),f(es))
    case _ => 
      printerr("FAIL: "+e)
      e
  }).asInstanceOf[Def[A]]

  override def containSyms(e: Any): List[Sym[Any]] = e match {
    case s: AbstractStruct[_] => Nil //ignore nested mutability for Structs: this is only safe because we rewrite mutations to atomic operations
    case NestedFieldUpdate(_,_,_) => Nil
    case _ => super.containSyms(e)
  }

  override def mirror[A:Manifest](e: Def[A], f: Transformer)(implicit ctx: SourceContext): Exp[A] = (e match {
    case Reflect(NestedFieldUpdate(struct, fields, rhs), u, es) => reflectMirrored(Reflect(NestedFieldUpdate(f(struct), fields, f(rhs)), mapOver(f,u), f(es)))(mtype(manifest[A]))
    case Reflect(x@IntTimes(a,b), u, es) => reflectMirrored(mirrorDD(e,f).asInstanceOf[Reflect[A]])
    case Reflect(x@IntPlus(a,b), u, es) => reflectMirrored(mirrorDD(e,f).asInstanceOf[Reflect[A]])
    case Reflect(x@IntMinus(a,b), u, es) => reflectMirrored(mirrorDD(e,f).asInstanceOf[Reflect[A]])
    case Reflect(x@IntMod(a,b), u, es) => reflectMirrored(mirrorDD(e,f).asInstanceOf[Reflect[A]])
    case Reflect(x@IntDivide(a,b), u, es) => reflectMirrored(mirrorDD(e,f).asInstanceOf[Reflect[A]])
    case Reflect(x@OrderingLT(a,b), u, es) => reflectMirrored(mirrorDD(e,f).asInstanceOf[Reflect[A]])
    case _ => super.mirror(e,f)
  }).asInstanceOf[Exp[A]]


  object StructType { //TODO: we should have a unified way of handling this, e.g., TypeTag[T] instead of Manifest[T]
    def unapply[T:Manifest](e: Exp[DeliteArray[T]]) = unapplyStructType[T]
    def unapply[T:Manifest] = unapplyStructType[T]
  }

  def unapplyStructType[T:Manifest]: Option[(StructTag[T], List[(String,Manifest[_])])] = manifest[T] match {
    case r: RefinedManifest[T] => Some(AnonTag(r), r.fields)
    case t if t.erasure == classOf[Tuple2[_,_]] => Some((classTag(t), List("_1","_2") zip t.typeArguments))
    case t if t.erasure == classOf[Tuple3[_,_,_]] => Some((classTag(t), List("_1","_2","_3") zip t.typeArguments))
    case t if t.erasure == classOf[Tuple4[_,_,_,_]] => Some((classTag(t), List("_1","_2","_3","_4") zip t.typeArguments))
    case t if t.erasure == classOf[Tuple5[_,_,_,_,_]] => Some((classTag(t), List("_1","_2","_3","_4","_5") zip t.typeArguments))
    case _ => None
  }

}


trait ScalaGenDeliteStruct extends BaseGenStruct {
  val IR: DeliteStructsExp with DeliteOpsExp
  import IR._

  override def emitNode(sym: Sym[Any], rhs: Def[Any]) = rhs match {
    //TODO: generalize primitive struct packing
    case Struct(tag, elems) =>
      registerStruct(structName(sym.tp), elems)
      emitValDef(sym, "new " + structName(sym.tp) + "(" + elems.map{ e => 
        if (isVarType(e._2.tp) && deliteInputs.contains(e._2)) quote(e._2) + ".get"
        else quote(e._2)
      }.mkString(",") + ")")
    case FieldApply(struct, index) =>
      emitValDef(sym, quote(struct) + "." + index)
      val lhs = struct match { case Def(lhs) => lhs.toString case _ => "?" }
    case FieldUpdate(struct, index, rhs) =>
      emitValDef(sym, quote(struct) + "." + index + " = " + quote(rhs))
      val lhs = struct match { case Def(lhs) => lhs.toString case _ => "?" }
    case NestedFieldUpdate(struct, fields, rhs) =>
      emitValDef(sym, quote(struct) + "." + fields.reduceLeft(_ + "." + _) + " = " + quote(rhs))
    case _ => super.emitNode(sym, rhs)
  }

  override def remap[A](m: Manifest[A]) = m match {
    case StructType(_,_) => "generated.scala." + structName(m)
    case s if s <:< manifest[Record] && s != manifest[Nothing] => "generated.scala." + structName(m)
    case _ => super.remap(m)
  }

  private def isVarType[T](m: Manifest[T]) = m.erasure.getSimpleName == "Variable" && unapplyStructType(m.typeArguments(0)) == None
  private def isArrayType[T](m: Manifest[T]) = m.erasure.getSimpleName == "DeliteArray"
  private def isStringType[T](m: Manifest[T]) = m.erasure.getSimpleName == "String"
  private def baseType[T](m: Manifest[T]) = if (isVarType(m)) mtype(m.typeArguments(0)) else m
  
  override def emitDataStructures(path: String) {
    val stream = new PrintWriter(path + "DeliteStructs.scala")
    stream.println("package generated.scala")
    for ((name, elems) <- encounteredStructs) {
      stream.println("")
      emitStructDeclaration(name, elems)(stream)
    }
    stream.close()
    super.emitDataStructures(path)
  }

  def emitStructDeclaration(name: String, elems: Seq[(String,Manifest[_])])(stream: PrintWriter) {
    stream.print("case class " + name + "(")
    stream.print(elems.map{ case (idx,tp) => "var " + idx + ": " + remap(tp) }.mkString(", "))
    stream.println(")")
  }
}

trait CLikeGenDeliteStruct extends BaseGenStruct with CLikeCodegen {
  val IR: DeliteStructsExp with DeliteOpsExp
  import IR._

  // Set of generated or generation-failed structs for this target
  protected val generatedStructs = HashSet[String]()
  protected val generationFailedStructs = HashSet[String]()

  override def remap[A](m: Manifest[A]) = m match {
    case StructType(_,_) => deviceTarget.toString + structName(m)
    case s if s <:< manifest[Record] && s != manifest[Nothing] => deviceTarget.toString + structName(m)
    case _ => super.remap(m)
  }

  protected def isVarType[T](m: Manifest[T]) = m.erasure.getSimpleName == "Variable" && unapplyStructType(m.typeArguments(0)) == None
  protected def isArrayType[T](m: Manifest[T]) = m.erasure.getSimpleName == "DeliteArray"
  protected def isStringType[T](m: Manifest[T]) = m.erasure.getSimpleName == "String"
  protected def baseType[T](m: Manifest[T]) = if (isVarType(m)) mtype(m.typeArguments(0)) else m
  protected def unwrapArrayType[T](m: Manifest[T]): Manifest[_] = baseType(m) match {
    case bm if isArrayType(bm) => unwrapArrayType(bm.typeArguments(0))
    case bm => bm 
  }
  protected def isNestedArrayType[T](m: Manifest[T]) = isArrayType(baseType(m)) && !isPrimitiveType(unwrapArrayType(m))

  override def emitDataStructures(path: String) {
    val structStream = new PrintWriter(path + deviceTarget.toString + "DeliteStructs.h")
    structStream.println("#ifndef __DELITESTRUCTS_H__")
    structStream.println("#define __DELITESTRUCTS_H__")
    structStream.println("#include \"" + hostTarget + "DeliteArray.h\"")
    structStream.println("#include \"" + deviceTarget + "DeliteArray.h\"")
    //println("Cuda Gen is generating " + encounteredStructs.map(_._1).mkString(","))
    for ((name, elems) <- encounteredStructs if !generatedStructs.contains(name)) {
      try {
        emitStructDeclaration(path, name, elems)
        //elems /*filterNot { e => isNestedArrayType(e._2) }*/ foreach { e => dsTypesList.add(baseType(e._2).asInstanceOf[Manifest[Any]]) }
        structStream.println("#include \"" + deviceTarget + name + ".h\"")
      }
      catch {
        case e: GenerationFailedException => generationFailedStructs += name 
        case e: Exception => throw(e)
      }
    }
    structStream.println("#endif")
    structStream.close()
    super.emitDataStructures(path)
  }

  def emitStructDeclaration(path: String, name: String, elems: Seq[(String,Manifest[_])])
  
}

trait CudaGenDeliteStruct extends CLikeGenDeliteStruct with CudaCodegen {
  val IR: DeliteStructsExp with DeliteOpsExp
  import IR._

  override def emitNode(sym: Sym[Any], rhs: Def[Any]) = rhs match {
    case Struct(tag, elems) =>
      registerStruct(structName(sym.tp), elems)
      // Within kernel, place on stack
      if(isNestedNode) {
        stream.println(remap(sym.tp) + " " + quote(sym) + " = " + remap(sym.tp) + "(" + elems.map{ e => 
          if (isVarType(e._2.tp) && deliteInputs.contains(e._2)) quote(e._2) + ".get()"
          else quote(e._2)
        }.mkString(",") + ");")
      }
      else {
        stream.println(remap(sym.tp) + " *" + quote(sym) + "_ptr = new " + remap(sym.tp) + "(" + elems.map{ e => 
          if (isVarType(e._2.tp) && deliteInputs.contains(e._2)) quote(e._2) + "->get()"
          else quote(e._2)
        }.mkString(",") + ");")
        stream.println(remap(sym.tp) + " " + quote(sym) + " = *" + quote(sym) + "_ptr;")
      }
      printlog("WARNING: emitting " + remap(sym.tp) + " struct " + quote(sym))    
    case FieldApply(struct, index) =>
      emitValDef(sym, quote(struct) + "." + index)
      printlog("WARNING: emitting field access: " + quote(struct) + "." + index)
    case FieldUpdate(struct, index, rhs) =>
      emitValDef(sym, quote(struct) + "." + index + " = " + quote(rhs))
      printlog("WARNING: emitting field update: " + quote(struct) + "." + index)
    case NestedFieldUpdate(struct, fields, rhs) =>
      emitValDef(sym, quote(struct) + "." + fields.reduceLeft(_ + "." + _) + " = " + quote(rhs))
    case _ => super.emitNode(sym, rhs)
  }

  def emitStructDeclaration(path: String, name: String, elems: Seq[(String,Manifest[_])]) {
    val stream = new PrintWriter(path + deviceTarget + name + ".h")
    try {
      stream.println("#ifndef __" + deviceTarget + name + "__")
      stream.println("#define __" + deviceTarget + name + "__")
      val dependentStructTypes = elems.map(e => 
        if(encounteredStructs.contains(structName(baseType(e._2)))) structName(baseType(e._2))
        else if(encounteredStructs.contains(structName(unwrapArrayType(e._2)))) structName(unwrapArrayType(e._2))
        else remap(baseType(e._2)).replaceAll(deviceTarget,"")  // SoA transfromed types
      ).distinct
      
      dependentStructTypes foreach { t =>
        if (encounteredStructs.contains(t)) {
          stream.println("#include \"" + deviceTarget + t + ".h\"") 
          if (generationFailedStructs.contains(t)) {
            throw new GenerationFailedException("Cannot generate struct " + name + " because of the failed dependency " + t)
          }
          else {
            emitStructDeclaration(path, t, encounteredStructs(t))
          }
        }
      }   
      
      if(isAcceleratorTarget)
        stream.println("#include \"" + hostTarget + name + ".h\"")

      stream.println("class " + deviceTarget + name + " {")
      // fields
      stream.println("public:")
      stream.print(elems.map{ case (idx,tp) => "\t" + remap(tp) + " " + idx + ";\n" }.mkString(""))
      // constructor
      stream.println("\t__host__ __device__ " + deviceTarget + name + "(void) { }")
      stream.print("\t__host__ __device__ " + deviceTarget + name + "(")
      stream.print(elems.map{ case (idx,tp) => remap(tp) + " _" + idx }.mkString(","))
      stream.println(") {")
      stream.print(elems.map{ case (idx,tp) => "\t\t" + idx + " = _" + idx + ";\n" }.mkString(""))
      stream.println("\t}")

      //TODO: Below should be changed to use IR nodes
      /*
      if(prefix == "") {
        stream.println("\t__device__ void dc_copy(" + name + " from) {")
        for((idx,tp) <- elems) {
          if(isPrimitiveType(baseType(tp))) stream.println("\t\t" + idx + " = from." + idx + ";")
          else stream.println("\t\t" + idx + ".dc_copy(from." + idx + ");")
        }
        stream.println("\t}")
        stream.println("\t__host__ " + name + " *dc_alloc() {")
        stream.print("\t\treturn new " + name + "(")
        stream.print(elems.map{ case (idx,tp) => if(!isPrimitiveType(baseType(tp))) ("*" + idx + ".dc_alloc()") else idx }.mkString(","))
        stream.println(");")
        stream.println("\t}")
        // Only generate dc_apply, dc_update, dc_size when there is only 1 DeliteArray among the fields
        val arrayElems = elems.filter(e => isArrayType(baseType(e._2)))
        val generateDC = arrayElems.size > 0
        val generateAssert = (arrayElems.size > 1) || (arrayElems.size==1 && generatedStructs.contains(remap(baseType(arrayElems(0)._2))))
        if(generateDC) {
          val (idx,tp) = elems.filter(e => isArrayType(baseType(e._2)))(0)
          val argtp = if(generateAssert) "int" else remap(unwrapArrayType(tp))
          stream.println("\t__host__ __device__ " + argtp + " dc_apply(int idx) {")
          if(generateAssert) stream.println("\t\tassert(false);")
          else stream.println("\t\treturn " + idx + ".apply(idx);")
          stream.println("\t}")
          stream.println("\t__host__ __device__ void dc_update(int idx," + argtp + " newVal) {")
          if(generateAssert) stream.println("\t\tassert(false);")
          else stream.println("\t\t" + idx + ".update(idx,newVal);")  
          stream.println("\t}")
          stream.println("\t__host__ __device__ int dc_size(void) {")
          if(generateAssert) stream.println("\t\tassert(false);")
          else stream.println("\t\treturn " + idx + ".length;")
          stream.println("\t}")
        }
      }
      */
      stream.println("};")
    
      // Wrapper class that holds both the device type and host type
      if(isAcceleratorTarget) {
        stream.println("class Host" + deviceTarget + name + " {")
        stream.println("public:")
        stream.println(hostTarget + name + " *host;")
        stream.println(deviceTarget + name + " *dev;")
        stream.println("};")
      }

      stream.println("#endif")
      generatedStructs += name
      stream.close()
      elems foreach { e => dsTypesList.add(baseType(e._2).asInstanceOf[Manifest[Any]]) }
      elems foreach { e => dsTypesList.add(unwrapArrayType(e._2).asInstanceOf[Manifest[Any]]) }
    }
    catch {
      case e: GenerationFailedException => generationFailedStructs += name; (new File(path + deviceTarget + name + ".h")).delete; throw(e)
      case e: Exception => throw(e)
    }
  }

}

trait OpenCLGenDeliteStruct extends CLikeGenDeliteStruct with OpenCLCodegen {
  val IR: DeliteStructsExp with DeliteOpsExp
  import IR._

  def emitStructDeclaration(path: String, name: String, elems: Seq[(String,Manifest[_])]) { }
}

trait CGenDeliteStruct extends CLikeGenDeliteStruct with CCodegen {
  val IR: DeliteStructsExp with DeliteOpsExp
  import IR._

  override def emitNode(sym: Sym[Any], rhs: Def[Any]) = rhs match {
    case Struct(tag, elems) =>
      registerStruct(structName(sym.tp), elems)
      /*
      emitValDef(sym, "(" + remap(sym.tp) + " *)malloc(sizeof(" + remap(sym.tp) + "));")
      stream.println(elems.map{ e =>
        quote(sym) + "->" + e._2 + " = " + 
        (if (isVarType(e._2.tp) && deliteInputs.contains(e._2)) quote(e._2) + "->get()"
        else quote(e._2)) + ";"
      }.mkString(""))
      */
      stream.println(remap(sym.tp) + " *" + quote(sym) + " = new " + remap(sym.tp) + "(" + elems.map{ e => 
         if (isVarType(e._2.tp) && deliteInputs.contains(e._2)) quote(e._2) + "->get()"
         else quote(e._2)
      }.mkString(",") + ");")
      printlog("WARNING: emitting " + remap(sym.tp) + " struct " + quote(sym))    
    case FieldApply(struct, index) =>
      emitValDef(sym, quote(struct) + "->" + index)
      printlog("WARNING: emitting field access: " + quote(struct) + "." + index)
    case FieldUpdate(struct, index, rhs) =>
      stream.println(quote(struct) + "->" + index + " = " + quote(rhs) + ";")
      printlog("WARNING: emitting field update: " + quote(struct) + "." + index)
    case NestedFieldUpdate(struct, fields, rhs) =>
      stream.println(quote(struct) + "->" + fields.reduceLeft(_ + "->" + _) + " = " + quote(rhs) + ";")
    case _ => super.emitNode(sym, rhs)
  }

  //TODO: Merge with other C-like codegen (when merged with wip-gpuref)
  // currently CUDA codegen uses stack for struct type datastructure.
  override def emitStructDeclaration(path: String, name: String, elems: Seq[(String,Manifest[_])]) {
    val stream = new PrintWriter(path + deviceTarget + name + ".h")
    try {
      stream.println("#ifndef __" + deviceTarget + name + "__")
      stream.println("#define __" + deviceTarget + name + "__")
      
      val dependentStructTypes = elems.map(e => 
        if(encounteredStructs.contains(structName(baseType(e._2)))) structName(baseType(e._2))
        else if(encounteredStructs.contains(structName(unwrapArrayType(e._2)))) structName(unwrapArrayType(e._2))
        else remap(baseType(e._2)).replaceAll(deviceTarget,"")  // SoA transfromed types
      ).distinct
        
      dependentStructTypes foreach { t =>
        if (encounteredStructs.contains(t)) {
          stream.println("#include \"" + deviceTarget + t + ".h\"") 
          if (generationFailedStructs.contains(t)) {
            throw new GenerationFailedException("Cannot generate struct " + name + " because of the failed dependency " + t)
          }
          else {
            emitStructDeclaration(path, t, encounteredStructs(t))
          }
        }
      }   
      
      if(isAcceleratorTarget)
        stream.println("#include \"" + hostTarget + name + ".h\"")

      stream.println("class " + deviceTarget + name + " {")
      // fields
      stream.println("public:")
      stream.print(elems.map{ case (idx,tp) => "\t" + remap(tp) + " " + addRef(baseType(tp)) + idx + ";\n" }.mkString(""))
      // constructor
      stream.println("\t" + deviceTarget + name + "(void) { }")
      stream.print("\t" + deviceTarget + name + "(")
      stream.print(elems.map{ case (idx,tp) => remap(tp) + addRef(baseType(tp)) + " _" + idx }.mkString(","))
      stream.println(") {")
      stream.print(elems.map{ case (idx,tp) => "\t\t" + idx + " = _" + idx + ";\n" }.mkString(""))
      stream.println("\t}")  
      stream.println("};")

      stream.println("#endif")
      generatedStructs += name
      stream.close()
      elems foreach { e => dsTypesList.add(baseType(e._2).asInstanceOf[Manifest[Any]]) }
      elems foreach { e => dsTypesList.add(unwrapArrayType(e._2).asInstanceOf[Manifest[Any]]) }
      //println("dsTyps:" + dsTypesList.toString)
    }
    catch {
      case e: GenerationFailedException => generationFailedStructs += name; (new File(path + deviceTarget + name + ".h")).delete; throw(e)
      case e: Exception => throw(e)
    }
  }
  
}

