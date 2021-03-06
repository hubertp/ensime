package org.ensime.model
import scala.collection.mutable.{ HashMap, ArrayBuffer }
import scala.tools.nsc.interactive.{ Global, CompilerControl }
import scala.tools.nsc.symtab.{ Symbols, Types }
import scala.tools.nsc.util.{ NoPosition, Position }

abstract class EntityInfo(val name: String, val members: Iterable[EntityInfo]) {}

class PackageInfo(override val name: String, val fullname: String, override val members: Iterable[EntityInfo]) extends EntityInfo(name, members) {}

class SymbolInfo(
  val name: String,
  val declPos: Position,
  val tpe: TypeInfo,
  val isCallable: Boolean) {}

class SymbolInfoLight(
  val name: String,
  val tpeSig: String,
  val tpeId: Int,
  val isCallable: Boolean) {}

class NamedTypeMemberInfo(override val name: String, val tpe: TypeInfo, val pos: Position, val declaredAs: scala.Symbol) extends EntityInfo(name, List()) {}
class NamedTypeMemberInfoLight(override val name: String, val tpeSig: String, val tpeId: Int, val isCallable: Boolean) extends EntityInfo(name, List()) {}

class TypeInfo(
  name: String,
  val id: Int,
  val declaredAs: scala.Symbol,
  val fullName: String,
  val args: Iterable[TypeInfo],
  members: Iterable[EntityInfo],
  val pos: Position,
  val outerTypeId: Option[Int]) extends EntityInfo(name, members) {}

class ArrowTypeInfo(
  override val name: String,
  override val id: Int,
  val resultType: TypeInfo,
  val paramTypes: Iterable[TypeInfo]) extends TypeInfo(name, id, 'nil, name, List(), List(), NoPosition, None) {}

class CallCompletionInfo(
  val resultType: TypeInfo,
  val paramTypes: Iterable[TypeInfo],
  val paramNames: Iterable[String]) {}

class InterfaceInfo(val tpe: TypeInfo, val viaView: Option[String]) {}

class TypeInspectInfo(val tpe: TypeInfo, val companionId: Option[Int], val supers: Iterable[InterfaceInfo]) {}

trait ModelBuilders { self: Global =>

  import self._
  import definitions.{ ObjectClass, ScalaObjectClass, RootPackage, EmptyPackage, NothingClass, AnyClass, AnyRefClass }

  private val typeCache = new HashMap[Int, Type]
  private val typeCacheReverse = new HashMap[Type, Int]

  def clearTypeCache() {
    typeCache.clear
    typeCacheReverse.clear
  }
  def typeById(id: Int): Option[Type] = {
    typeCache.get(id)
  }
  def cacheType(tpe: Type): Int = {
    if (typeCacheReverse.contains(tpe)) {
      typeCacheReverse(tpe)
    } else {
      val id = typeCache.size + 1
      typeCache(id) = tpe
      typeCacheReverse(tpe) = id
      id
    }
  }

  object Helpers {

    import scala.tools.nsc.symtab.Flags._

    /* See source at root/scala/trunk/src/compiler/scala/tools/nsc/symtab/Symbols.scala  
    for details on various symbol predicates. */
    def declaredAs(sym: Symbol): scala.Symbol = {
      if (sym.isMethod)
        'method
      else if (sym.isTrait)
        'trait
      else if (sym.isTrait && sym.hasFlag(JAVA))
        'interface
      else if (sym.isInterface)
        'interface
      else if (sym.isModule)
        'object
      else if (sym.isModuleClass)
        'object
      else if (sym.isClass)
        'class
      else if (sym.isPackageClass)
        'class

      // check this last so objects are not
      // classified as fields
      else if (sym.isValue || sym.isVariable)
        'field
      else 'nil
    }

    def isArrowType(tpe: Type) = {
      tpe match {
        case _: MethodType => true
        case _: PolyType => true
        case _ => false
      }
    }

    def isNoParamArrowType(tpe: Type) = {
      tpe match {
        case t: MethodType => t.params.isEmpty
        case t: PolyType => t.params.isEmpty
        case t: Type => false
      }
    }

    def typeOrArrowTypeResult(tpe: Type) = {
      tpe match {
        case t: MethodType => t.resultType
        case t: PolyType => t.resultType
        case t: Type => t
      }
    }

    def normalizeSym(aSym: Symbol): Symbol = aSym match {
      case null | EmptyPackage | NoSymbol => normalizeSym(RootPackage)
      case ScalaObjectClass | ObjectClass => normalizeSym(AnyRefClass)
      case _ if aSym.isModuleClass || aSym.isPackageObject => normalizeSym(aSym.sourceModule)
      case _ => aSym
    }

    /**
     * Conveniance method to generate a String describing the type. Omit
     * the package name. Include the arguments postfix.
     * 
     * Used for type-names of symbol and member completions
     */
    def typeShortNameWithArgs(tpe: Type): String = {
      if (isArrowType(tpe)) {
        ("(" +
          tpe.paramTypes.map(typeShortNameWithArgs).mkString(", ") +
          ") => " +
          typeShortNameWithArgs(tpe.resultType))
      } else {
        (typeShortName(tpe) + (if (tpe.typeArgs.length > 0) {
          "[" +
            tpe.typeArgs.map(typeShortNameWithArgs).mkString(", ") +
            "]"
        } else { "" }))
      }
    }

    /** 
     * Generate qualified name, without args postfix.
     */
    def typeFullName(tpe: Type): String = {
      def nestedClassName(sym: Symbol): String = {
        outerClass(sym) match {
          case Some(outerSym) => {
            nestedClassName(outerSym) + "$" + typeShortName(sym)
          }
          case None => typeShortName(sym)
        }
      }
      val typeSym = tpe.typeSymbol
      if (typeSym.isNestedClass) {
        typeSym.enclosingPackage.fullName + "." + nestedClassName(typeSym)
      } else {
        typeSym.enclosingPackage.fullName + "." + typeShortName(typeSym)
      }
    }

    def typeShortName(tpe: Type): String = {
      if (tpe.typeSymbol != NoSymbol) typeShortName(tpe.typeSymbol)
      else tpe.toString
    }

    def typeShortName(sym: Symbol): String = {
      if (sym.isModule || sym.isModuleClass) sym.nameString + "$"
      else sym.nameString
    }

    /* Give the outerClass of a symbol representing a nested type */
    def outerClass(typeSym: Symbol): Option[Symbol] = {
      try {
        if (typeSym.isNestedClass) {
          Some(typeSym.outerClass)
        } else None
      } catch {
        // TODO accessing outerClass sometimes throws java.lang.Error
        // Notably, when tpe = scala.Predef$Class
        case e: java.lang.Error => None
      }
    }

    def companionTypeOf(tpe: Type): Option[Type] = {
      val sym = tpe.typeSymbol
      if (sym != NoSymbol) {
        if (sym.isModule || sym.isModuleClass) {
          val comp = sym.companionClass
          if (comp != NoSymbol && comp.tpe != tpe) {
            Some(comp.tpe)
          } else None
        } else if (sym.isTrait || sym.isClass || sym.isPackageClass) {
          val comp = sym.companionModule
          if (comp != NoSymbol && comp.tpe != tpe) {
            Some(comp.tpe)
          } else None
        } else None
      } else None
    }

    // When inspecting a type, transform a raw list of TypeMembers to a sorted
    // list of InterfaceInfo objects, each with its own list of sorted member infos.
    def prepareSortedInterfaceInfo(members: Iterable[Member]): Iterable[InterfaceInfo] = {
      // ...filtering out non-visible and non-type members
      val visMembers: Iterable[TypeMember] = members.flatMap {
        case m@TypeMember(sym, tpe, true, _, _) => List(m)
        case _ => List()
      }

      // Create a list of pairs [(typeSym, membersOfSym)]
      val membersByOwner = visMembers.groupBy {
        case TypeMember(sym, _, _, _, _) => {
          sym.owner
        }
      }.toList.sortWith {
        // Sort the pairs on the subtype relation
        case ((s1, _), (s2, _)) => s1.tpe <:< s2.tpe
      }

      membersByOwner.map {
        case (ownerSym, members) => {

          // If all the members in this interface were
          // provided by the same view, remember that 
          // view for later display to user.
          val byView = members.groupBy(_.viaView)
          val viaView = if (byView.size == 1) {
            byView.keys.headOption.filter(_ != NoSymbol)
          } else { None }

          // Do one top level sort by name on members, before
          // subdividing into kinds of members.
          val sortedMembers = members.toList.sortWith { (a, b) =>
            a.sym.nameString <= b.sym.nameString
          }

          // Convert type members into NamedTypeMemberInfos
          // and divided into different kinds..

          val nestedTypes = new ArrayBuffer[NamedTypeMemberInfo]()
          val constructors = new ArrayBuffer[NamedTypeMemberInfo]()
          val fields = new ArrayBuffer[NamedTypeMemberInfo]()
          val methods = new ArrayBuffer[NamedTypeMemberInfo]()

          for (tm <- sortedMembers) {
            val info = NamedTypeMemberInfo(tm)
            val decl = info.declaredAs
            if (decl == 'method) {
              if (info.name == "this") {
                constructors += info
              } else {
                methods += info
              }
            } else if (decl == 'field) {
              fields += info
            } else if (decl == 'class || decl == 'trait ||
              decl == 'interface || decl == 'object) {
              nestedTypes += info
            }
          }

          val sortedInfos = nestedTypes ++ fields ++ constructors ++ methods

          new InterfaceInfo(TypeInfo(ownerSym.tpe, sortedInfos),
            viaView.map(_.name.toString))
        }
      }
    }

  }

  import Helpers._

  object PackageInfo {

    def root: PackageInfo = fromSymbol(RootPackage)

    def fromPath(path: String): PackageInfo = {
      val pack = packageSymFromPath(path)
      pack match {
        case Some(packSym) => fromSymbol(packSym)
        case None => nullInfo
      }
    }

    private def packageSymFromPath(path: String): Option[Symbol] = {
      val pathSegs = path.split("\\.")
      val pack = pathSegs.foldLeft(RootPackage) { (packSym, seg) =>
        val member = packSym.info.members find { s =>
          s.nameString == seg && s != EmptyPackage && s != RootPackage
        }
        member.getOrElse(packSym)
      }
      if (pack == RootPackage) None
      else Some(pack)
    }

    def nullInfo = {
      new PackageInfo("NA", "NA", List())
    }

    def fromSymbol(aSym: Symbol): PackageInfo = {
      val bSym = normalizeSym(aSym)
      val bSymFullName = bSym.fullName
      def makeMembers(symbols: Iterable[Symbol]) = {
        val validSyms = symbols.filter { s =>
          s != EmptyPackage && s != RootPackage && s.owner.fullName == bSymFullName
        }
        val members = validSyms.flatMap(packageMemberFromSym).toList
        members.sortWith { (a, b) => a.name <= b.name }
      }

      val pack = if (bSym == RootPackage) {
        val memberSyms = (bSym.info.members ++ EmptyPackage.info.members)
        new PackageInfo(
          "root",
          "_root_",
          makeMembers(memberSyms)
          )
      } else {
        val memberSyms = bSym.info.members
        new PackageInfo(
          bSym.name.toString,
          bSym.fullName,
          makeMembers(memberSyms)
          )
      }
      pack
    }

    def packageMemberFromSym(aSym: Symbol): Option[EntityInfo] = {
      val bSym = normalizeSym(aSym)
      if (bSym == RootPackage) {
        Some(root)
      } else if (bSym.isPackage) {
        Some(fromSymbol(bSym))
      } else if (!(bSym.nameString.contains("$")) && (bSym != NoSymbol) && (bSym.tpe != NoType)) {
        if (bSym.isClass || bSym.isTrait || bSym.isModule ||
          bSym.isModuleClass || bSym.isPackageClass) {
          Some(TypeInfo(bSym.tpe))
        } else {
          None
        }
      } else {
        None
      }
    }

  }

  object TypeInfo {

    def apply(t: Type, members: Iterable[EntityInfo] = List()): TypeInfo = {
      val tpe = t match {
        // TODO: Instead of throwing away this information, would be better to 
        // alert the user that the type is existentially quantified.
        case et: ExistentialType => et.underlying
        case t => t
      }
      tpe match {
        case tpe: MethodType => ArrowTypeInfo(tpe)
        case tpe: PolyType => ArrowTypeInfo(tpe)
        case tpe: Type =>
          {
            val params = tpe.typeParams.map(_.toString)
            val args = tpe.typeArgs.map(TypeInfo(_))
            val typeSym = tpe.typeSymbol
            val outerTypeId = outerClass(typeSym).map(s => cacheType(s.tpe))
            new TypeInfo(
              typeShortName(tpe),
              cacheType(tpe),
              declaredAs(typeSym),
              typeFullName(tpe),
              args,
              members,
              typeSym.pos,
              outerTypeId
              )
          }
        case _ => nullInfo
      }
    }

    def nullInfo() = {
      new TypeInfo("NA", -1, 'nil, "NA", List(), List(), NoPosition, None)
    }
  }

  object CallCompletionInfo {

    def apply(tpe: Type): CallCompletionInfo = {
      tpe match {
        case tpe: MethodType => apply(tpe.paramTypes, tpe.params, tpe.resultType)
        case tpe: PolyType => apply(tpe.paramTypes, tpe.params, tpe.resultType)
        case _ => nullInfo
      }
    }

    def apply(paramTypes: Iterable[Type], paramNames: Iterable[Symbol], resultType: Type): CallCompletionInfo = {
      new CallCompletionInfo(
        TypeInfo(resultType),
        paramTypes.map(t => TypeInfo(t)),
        paramNames.map(s => s.nameString)
        )
    }

    def nullInfo() = {
      new CallCompletionInfo(TypeInfo.nullInfo, List(), List())
    }
  }

  object SymbolInfo {

    def apply(sym: Symbol): SymbolInfo = {
      new SymbolInfo(
        sym.name.toString,
        sym.pos,
        TypeInfo(sym.tpe),
        Helpers.isArrowType(sym.tpe)
        )
    }

    def nullInfo() = {
      new SymbolInfo("NA", NoPosition, TypeInfo.nullInfo, false)
    }

  }

  object SymbolInfoLight {

    /** 
     *  Return symbol infos for any like-named constructors.
     */
    def constructorSynonyms(sym: Symbol): List[SymbolInfoLight] = {
      val members = if (sym.isClass || sym.isPackageClass) {
        sym.tpe.members
      } else if (sym.isModule || sym.isModuleClass) {
        sym.companionClass.tpe.members
      } else { List() }

      members.flatMap { member: Symbol =>
        if (member.isConstructor) {
          Some(SymbolInfoLight(sym, member.tpe))
        } else { None }
      }
    }

    /** 
     *  Return symbol infos for any like-named apply methods.
     */
    def applySynonyms(sym: Symbol): List[SymbolInfoLight] = {
      val members = if (sym.isModule || sym.isModuleClass) {
        sym.tpe.members
      } else if (sym.isClass || sym.isPackageClass) {
        sym.companionModule.tpe.members
      } else { List() }
      members.flatMap { member: Symbol =>
        if (member.name.toString == "apply") {
          Some(SymbolInfoLight(sym, member.tpe))
        } else { None }
      }
    }

    def apply(sym: Symbol): SymbolInfoLight = SymbolInfoLight(sym, sym.tpe)

    def apply(sym: Symbol, tpe: Type): SymbolInfoLight = {
      new SymbolInfoLight(
        sym.nameString,
        typeShortNameWithArgs(tpe),
        cacheType(tpe.underlying),
        Helpers.isArrowType(tpe.underlying)
        )
    }

    def nullInfo() = {
      new SymbolInfoLight("NA", "NA", -1, false)
    }
  }

  object NamedTypeMemberInfo {
    def apply(m: TypeMember): NamedTypeMemberInfo = {
      val decl = declaredAs(m.sym)
      new NamedTypeMemberInfo(m.sym.nameString, TypeInfo(m.tpe), m.sym.pos, decl)
    }
  }

  object NamedTypeMemberInfoLight {
    def apply(m: TypeMember): NamedTypeMemberInfoLight = {
      new NamedTypeMemberInfoLight(m.sym.nameString,
        typeShortNameWithArgs(m.tpe),
        cacheType(m.tpe),
        isArrowType(m.tpe))
    }
  }

  object ArrowTypeInfo {

    def apply(tpe: Type): ArrowTypeInfo = {
      tpe match {
        case tpe: MethodType => apply(tpe, tpe.paramTypes, tpe.params, tpe.resultType)
        case tpe: PolyType => apply(tpe, tpe.paramTypes, tpe.params, tpe.resultType)
        case _ => nullInfo
      }
    }

    def apply(tpe: Type, paramTypes: Iterable[Type], paramNames: Iterable[Symbol], resultType: Type): ArrowTypeInfo = {
      new ArrowTypeInfo(
        tpe.toString,
        cacheType(tpe),
        TypeInfo(tpe.resultType),
        tpe.paramTypes.map(t => TypeInfo(t)))
    }

    def nullInfo() = {
      new ArrowTypeInfo("NA", -1, TypeInfo.nullInfo, List())
    }
  }

  object TypeInspectInfo {
    def nullInfo() = {
      new TypeInspectInfo(TypeInfo.nullInfo(), None, List())
    }
  }

}
