package compilerPlugin

import ProtocolDSL.{ReturnValue, State}
import compilerPlugin.Util._

import scala.collection.{SortedSet, mutable}
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.reflect.api.Trees
import scala.tools.nsc.plugins.{Plugin, PluginComponent}
import scala.tools.nsc.{Global, Phase}
import scala.util.control.Breaks._


/** My plugin */
class GetFileFromAnnotation(val global: Global) extends Plugin {
  val name = "GetFileFromAnnotation"
  val description = "Checks the protocol defined on a class or object with the Typestate annotation"
  lazy val components =
    new MyComponent(global) :: Nil
}

/** The component which will run when my plugin is used */
class MyComponent(val global: Global) extends PluginComponent {
  import global._

  /** Holds information about a function in the code
   *
   *
   * @param name : Name of the function
   * @param params : The parameters of the function, stored in an array with name, type
   * @param returnType : The return type of the function, if it returns anything
   * @param body : The body of the function (the code inside of it)
   * @param scope : The scope of the function
   * @param stateCache : contains a mapping of parameters (elementType, parameterNames, statesBefore) to nextStates.
   *                   There can be several parameter names if a function is called with duplicate values as arguments.
   * @param returned : The instances if any, that this function returns
   */
  case class Function(name: String, params: ArrayBuffer[(String, String)],
                      returnType: Trees#Tree, body: Trees#Tree, scope: mutable.Stack[String],
                      var stateCache: Map[ArrayBuffer[(String, Set[String], Set[State])], ArrayBuffer[Set[State]]],
                      var returned: Option[Set[Instance]]) { //might replace string with elementInfo for more precision (on build)
    override def toString: String = {
      s"name: $name parameters: $params return type: $returnType scope: $scope body: $body stateCache: $stateCache returned: $returned"
    }
  }

  /** Holds information about an element (a class or object)
   *
   * @param elementType : type of the element, this gives a more precise way of identifying the element than just the name
   * @param params : parameters given to initialise a class. Not applicable to objects
   * @param body : body of the element
   * @param scope : scope of the element
   * @param isObject : true if the element is an object, false if it is a class. Defaults to false
   * @param initialised : only relevant if the element is an object. Keeps track of if this object has been initialised
   */
  case class Element(elementType:String, params:ArrayBuffer[(String, String)], body:Seq[Trees#Tree], scope:mutable.Stack[String],
                     isObject:Boolean=false, var initialised:Boolean=false){
    override def toString(): String={ s"$elementType ${showParams(params)} $scope $initialised" }

    def showParams(params:ArrayBuffer[(String, String)]):String={
      var parameters = ""
      for(param <- params) {
        parameters += param._1 + ": "+param._2 +  " ; "
      }
      parameters
    }
  }

  val runsAfter: List[String] = List[String]("refchecks")
  val phaseName: String = "compilerPlugin.GetFileFromAnnotation.this.name"

  def newPhase(_prev: Phase) = new GetFileFromAnnotationPhase(_prev)

  /** Phase which is run by the plugin */
  class GetFileFromAnnotationPhase(prev: Phase) extends StdPhase(prev) {
    override def name: String = "compilerPlugin.GetFileFromAnnotation.this.name"
    var compilationUnit: CompilationUnit = _

    /** Used to differentiate between different types of loop.
     *  Contains values: forLoop, whileLoop, dowhileLoop and trueLoop (for while(true) or dowhile(true) loops)
     *
     */
    object LoopType extends Enumeration{
      type LoopType = Value
      val forLoop, whileLoop, dowhileLoop, trueLoop = Value
    }

    /** Contains the state of instances at a break statement
     *  Structured as a map of breakable label -> elementType -> instances
     *
     */
    var savedBreakInstances: mutable.Map[String, mutable.Map[String, ArrayBuffer[Set[Instance]]]] =
      mutable.Map[String, mutable.Map[String, ArrayBuffer[Set[Instance]]]]()


    /** Entry point of the plugin.
     *  In a first pass, goes through the code collecting element and function information.
     *  Then it finds all the elements which have a Typestate annotation and creates an entry in the protocolledElemenets
     *  map for each of them.
     *  It then checks the code for protocol violations.
     *
     * @param unit : contains tree of the code in body
     */
    def apply(unit: CompilationUnit): Unit = {
      println("hello, plugin is running")
      //println(s"whole source is: \n ${unit.body}")
      println("raw is: "+showRaw(unit.body))
      compilationUnit = unit
      functionTraverser.traverse(unit.body)
      ElementTraverser.traverse(unit.body)
      checkFile()
    }

    /** Checks that a class or object is following its protocol
     * Goes through the code to find either the object with App or the main function and thereby gets the entrypoint
     * of the code and can start analysing it from there.
     *
     * Limited at the moment
     *
     * */
    def checkFile(): Unit = {
      for (line@q"$mods object $tname extends { ..$earlydefns } with ..$parents { $self => ..$body }" <- compilationUnit.body) {
        breakable {
          for (parent <- parents) {
            if (parent.toString() == "App") {
              currentScope = getScope(line)
              for (element <- ElementTraverser.elements if
              (element.elementType == line.symbol.tpe.toString() && element.scope == getScope(line)))
                element.initialised = true
              currentScope.push(tname.toString())
              initObjects()
              checkInsideObjectBody(body)
              break
            }
          }
          for (definition <- body) {
            definition match {
              case mainLine@q"$mods def main[..$tparams](...$paramss): $tpt = $expr" =>
                currentScope = getScope(line)
                /*_*/
                if (getParameterTypes(paramss) == "Array[String]") {
                  /*_*/
                  checkObject(line.symbol.tpe.toString(), tname.toString())
                  currentScope.push(tname.toString())
                  initObjects()
                  currentScope.push("main")
                  checkInsideFunctionBody(expr)
                }
              case _ =>
            }
          }
        }
      }
    }


    /** Goes inside an object to see if there are instances with protocols and if they are following their protocol
     *  Analyses the code line by line with checkInsideFunctionBody.
     *
     * @param code The code to check
     */
    def checkInsideObjectBody(code: Seq[Trees#Tree]) = {
      for(line <- code) {
        checkInsideFunctionBody(line)
      }
      printInstances()
    }


    /** Checks the code inside a function for protocol violations.
     *  Goes line by line and skips lines if needed (for example when at a definition or a loop).
     *  Returns a set of instances wrapped in an option
     *
     * @param code  The code to check
     * @return  What is returned from the function, if applicable
     */
    def checkInsideFunctionBody(code: Trees#Tree): (Option[Set[Instance]]) = {
      var returned: Option[Set[Instance]] = None
      var nbOfLinesToSkip = 0
      for (line <- code) {
        breakable {
          if (nbOfLinesToSkip > 0) {
            nbOfLinesToSkip -= 1
            break
          }
          val NbLinesToSkipAndReturned = processLine(line)
          nbOfLinesToSkip = NbLinesToSkipAndReturned._1
          println("lines to skip is "+nbOfLinesToSkip)
          if(nbOfLinesToSkip == -1) //indicates a break statement has just been processed
            return None
          println("returned before is is "+NbLinesToSkipAndReturned._2)
          if (NbLinesToSkipAndReturned._2 == null || NbLinesToSkipAndReturned._2.isDefined) // do this to avoid erasing returned element
            returned = NbLinesToSkipAndReturned._2
          println("returned after if is "+returned)
        }
      }
      printInstances()
      println(s"returned from $code is "+returned)
      returned
    }


    def getObjectName(rawObjectName: String):String = {
      if (rawObjectName.lastIndexOf(".") != -1) {
        val objectName = rawObjectName.substring(rawObjectName.lastIndexOf(".") + 1)
        return objectName
      }
      rawObjectName
    }

    /** Checks a line and updates instances if needed.
     *  Returns a set of instances if relevant.
     *  Has different cases for different types of line.
     *
     * @param line  line of code to analyse
     * @return Set of instances returned by the line
     */
    def processLine(line: Trees#Tree): (Int, Option[Set[Instance]]) = {
      println(s"checking line ${showRaw(line)} at line number " + line.pos.line)
      println(s"instances before checking line are ")
      printInstances()
      line match {
        //definitions to skip over (object, class, function)
        case q"$modifs object $tname extends { ..$earlydefins } with ..$pparents { $sself => ..$body }" =>
          (getLengthOfTree(line) - 1, None)
        case q"$mod class $pname[..$tpara] $actMods(...$para) extends { ..$defs } with ..$prnts { $self => ..$sts }" =>
          (getLengthOfTree(line) - 1, None)
        case q"$mods def $name[..$tparams](...$paramss): $tpt = $expr" =>
          (getLengthOfTree(line) - 1, None)
        //break and breakable
        case Apply(Select(Select(scope, label), TermName("break")), body) =>
          dealWithBreak(label.toString())
          removeAllInstances()
          (-1, None)
        case Apply(Select(Ident(TermName(label)), TermName("break")), List()) =>
          dealWithBreak(label)
          removeAllInstances()
          (-1, None)
        case Apply(Select(Select(scope, label), TermName("breakable")), body) =>
          dealWithBreakable(label, body)
          (getLengthOfTree(line) - 1, None)
        case Apply(Select(Ident(label), TermName("breakable")), body) =>
          dealWithBreakable(label, body)
          (getLengthOfTree(line) - 1, None)
        //assignments
        case ValDef(mods, TermName(assignee), assigneeType, Literal(Constant(null)))  =>
          println("recognised null in assignment")
          println("type is "+assigneeType)
          if(protocolledElements.contains(assigneeType.toString))
            protocolledElements(assigneeType.toString).instances +=
              Instance(Set(Alias(assignee, currentScope.clone())), Set(State(Undefined, -1)))
          (getLengthOfTree(line) - 1, None)
        case ValDef(mods, TermName(assignee), assigneeType, EmptyTree) =>
          println("recognised _ in assignment")
          println("type is "+assigneeType)
          if(protocolledElements.contains(assigneeType.toString))
            protocolledElements(assigneeType.toString).instances +=
              Instance(Set(Alias(assignee, currentScope.clone())), Set(State(Undefined, -1)))
          (getLengthOfTree(line) - 1, None)
        case ValDef(mods, TermName(assignee), assigneeType, assigned) =>
          /*_*/ processNovelAssignment(assignee, assigneeType.toString, assigned) /*_*/
          (getLengthOfTree(line) - 1, None)
        case q"val $assignee = $newValue" =>
          println("recognised assignment from match")
          /*_*/ processNovelAssignment(assignee.toString, newValue.tpe.toString, newValue) /*_*/
          (getLengthOfTree(line) - 1, None)
        case q"var $assignee = $newValue" =>
          /*_*/ processNovelAssignment(assignee.toString, newValue.tpe.toString, newValue) /*_*/
          (getLengthOfTree(line) - 1, None)
        case q"$assignee = $newValue" =>
          processAssignment(assignee.toString, newValue.tpe.toString, newValue)
          (getLengthOfTree(line) - 1, None)
        //for loops
        case q"for (..$generator) $loopBody" =>
          dealWithLoop(LoopType.forLoop, loopBody, generator = generator)
          (getLengthOfTree(line) - 1, None) //-1 because we are processing the current one already
        case q"for (..$generator) yield $loopBody" =>
          dealWithLoop(LoopType.forLoop, loopBody, generator = generator)
          (getLengthOfTree(line) - 1, None) //-1 because we are processing the current one already
        //while(true) and dowhile(true)
        case LabelDef(TermName(name), List(), block@Block(statements, Apply(Ident(TermName(name2)), List())))
          if (name.startsWith("while$") || name.startsWith("doWhile$")) && name2 == name =>
          dealWithLoop(LoopType.trueLoop, block.asInstanceOf[Trees#Tree])
          removeAllInstances() //can set all instances empty since normal code execution will end in while
          // true loop and breakable will take care of the ones which have a break statement
          (getLengthOfTree(line) - 1, None) //-1 because we are processing the current one already
        //while loops
        case q"while ($cond) $loopContents" =>
          dealWithLoop(LoopType.whileLoop, loopContents, cond = cond)
          (getLengthOfTree(line) - 1, None) //-1 because we are processing the current one already
        case q"do $loopContents while ($cond)" =>
          dealWithLoop(LoopType.dowhileLoop, loopContents, cond = cond)
          (0, None)
        //Functions (first is for functions defined in the same scope, second the others)
        case func@Apply(Ident(functionName), args) =>
          var copiedMap = copyMap(protocolledElements)
          val returned = dealWithFunction(func, functionName, args)
          updateStateIfNeeded(copiedMap, line)
          (getLengthOfTree(line) - 1, returned) //because we are processing the current one already
        case func@Apply(Select(instanceCalledOn, functionName), args) =>
          println("found function with called on "+instanceCalledOn)
          var copiedMap = copyMap(protocolledElements)
          val returned = dealWithFunction(func, functionName, args, instanceCalledOn)
          updateStateIfNeeded(copiedMap, line)
          (getLengthOfTree(line) - 1, returned) //because we are processing the current one already
        //if, try and match statements
        case q"if ($cond) $ifBody else $elseBody" =>
          val returned = dealWithIfElse(cond, ifBody, elseBody)
          (getLengthOfTree(line) - 1, returned)
        case q"try $tryBody catch { case ..$cases } finally $finallyBody" =>
          /*_*/ checkTryCatchFinally(tryBody, finallyBody) /*_*/
          (getLengthOfTree(line) - 1, None)
        case q"$expr match { case ..$cases }" =>
          /*_*/ processMatchStatement(expr, cases) /*_*/
          (getLengthOfTree(line) - 1, None)
        //All three next cases are to check for a solitary object name on a line
        case Ident(TermName(objectName)) =>
          checkObject(line.symbol.typeSignature.toString, objectName)
          if(line.tpe == null) return (0,None)
          getClosestScopeAliasInfo(objectName, line.symbol.typeSignature.toString) match {
            case Some(aliasInfo) =>
              val returned = protocolledElements(line.symbol.typeSignature.toString).instances.filter(instance => instance.containsAliasInfo(aliasInfo._1, aliasInfo._2))
              (0, Some(returned))
            case None =>
              (0, None)
          }
        case Select(location, expr) =>
          val objectName = getObjectName(expr.toString())
          checkObject(line.symbol.typeSignature.toString, objectName)
          getClosestScopeAliasInfo(objectName.trim, line.symbol.typeSignature.toString) match {
            case Some(aliasInfo) =>
              val returned = protocolledElements(line.symbol.typeSignature.toString).instances.filter(instance => instance.containsAliasInfo(aliasInfo._1, aliasInfo._2))
              (0, Some(returned))
            case None =>
              (0, None)
          }
        case Block(List(expr), Literal(Constant(()))) =>
          val objectName = getObjectName(expr.toString())
          checkObject(expr.tpe.toString(), objectName)
          getClosestScopeAliasInfo(objectName.trim, expr.tpe.toString()) match {
            case Some(aliasInfo) =>
              val returned = protocolledElements(expr.tpe.toString()).instances.filter(instance => instance.containsAliasInfo(aliasInfo._1, aliasInfo._2))
              (0, Some(returned))
            case None =>
              (0, None)
          }
        case q"null" =>
          println("recognised null")
          (0, null)
        case _ =>
          (0, None)
      }
    }

    /** Deals with a break statement in the code.
     *  Saves the current state of the instances inside savedBreakInstances
     *
     * @param label : Label of the break statement
     */
    private def dealWithBreak(label: String) = {
      for ((elementName, savedInstances) <- savedBreakInstances) {
        if (savedInstances.contains(label)) {
          savedInstances(label) += copyInstances(protocolledElements(elementName).instances)
        } else
          savedInstances += (label -> ArrayBuffer(copyInstances(protocolledElements(elementName).instances)))
        println(s"after dealing with break with label $label, saved instances are " + savedInstances)
      }
    }

    /** Deals with breakable in the code.
     *  Checks inside its body.
     *  After this, gathers the instances saved under its label during break statements and merges them together.
     *  Then removes its label from the savedBreakInstances map
     *
     * @param label : Label of the breakable statement
     * @param body : Contents of breakable
     */
    def dealWithBreakable(label:Name, body:Seq[Trees#Tree]) = {
      checkInsideObjectBody(body)
      for ((elementName, savedInstances) <- savedBreakInstances) {
        if (savedInstances.contains(label.toString())) {
          for (instances <- savedInstances(label.toString()))
            protocolledElements(elementName).instances =
              mergeInstanceStates(protocolledElements(elementName).instances, instances)
        }
        println("at the end of breakable, instances are " + protocolledElements(elementName).instances)
        savedInstances.remove(label.toString())
      }
    }

    /** Processes an assignment to an existing object.
     *  First, gets what is returned from the rhs of the assignment operation.
     *  Then, checks if the assignee is an alias of an instance.
     *  If so, removes that alias from the instances (it is getting replaced by the rhs)
     *  Then, add the alias to all the assigned instances (if there are assigned instances).
     *
     * @param assignee in x = y, x
     * @param assigneeType type of x
     * @param assigned  in x = y, y
     */
    def processAssignment(assignee: String, assigneeType: String, assigned: Trees#Tree) = {
      println("assignee is "+assignee)
      println("assigned is "+assigned)
      val returnedAssigned = checkInsideFunctionBody(assigned)
      println("returnedAssigned is "+returnedAssigned)
      getClosestScopeAliasInfo(assignee, assigneeType) match {
        case Some(assigneeAliasInfo) =>
          returnedAssigned match {
            case Some(returned) =>
              val returnedInstances = Util.copyInstances(returned) //keep this or function.returned might get overwritten
              removeAliases(assigneeType, assigneeAliasInfo._1)
              addAssigneeToAssigned(assigneeAliasInfo._1, assigneeAliasInfo._2, assigneeType, returnedInstances)
            case None =>
          }
        case None =>
      }
    }


    /** Processes a val/var assignee = assigned statement
     *  First, gets what is returned from the rhs of the assignment operation.
     *  Then, if the assigned is a set of instances, adds the assignee alias to the instances
     *  Along the way, gathers the scopes of instances that should be removed and removes them.
     *
     * @param assignee  In val/var x = y, this is x. Always comes as a new val or var.
     * @param assigned  In val/var x = y, this is y.
     */
    def processNovelAssignment(assignee: String, assigneeType: String, assigned: Trees#Tree) = {
      println("in process novel")
      println(s"assignee is $assignee and assigned is $assigned")
      println("raw assigned is "+showRaw(assigned))
      val returnedAssigned = checkInsideFunctionBody(assigned)
      println("returned is "+returnedAssigned)
      returnedAssigned match {
        case Some(returned) =>
          val returnedInstances = Util.copyInstances(returned) //keep this or function.returned might get overwritten
          addAssigneeToAssigned(assignee, currentScope.clone(), assigneeType, returnedInstances)
        case null =>
          println("got case null")
          protocolledElements(assigneeType).instances += Instance(Set(Alias(assignee, currentScope.clone())), Set(State(Undefined, -1)))
        case None =>
      }
      println("after novel assignment, instances are "+protocolledElements)
    }

    /** Adds the assignee alias (assigneeName, assigneeScope) to the assigned instances.
     *  If the returned instances are already in the map, add the assignee to them
     *  If they don't, add the alias to them and then add them into the instance map.
     *  Along the way, gathers the scopes of instances that should be removed and removes them.
     *
     * @param assigneeName Name of the assignee
     * @param assigneeScope Scope of the assignee (already existing scope or current scope)
     * @param assigneeType Type of the assignee
     * @param assigned Set of instances to be assigned the assignee
     */
    def addAssigneeToAssigned(assigneeName: String, assigneeScope: mutable.Stack[String], assigneeType: String,
                              assigned: Set[Instance]) = {
      var scopesToRemove: ArrayBuffer[mutable.Stack[String]] = ArrayBuffer()
      for (instance <- assigned) {
        if (instance.containsScopeAlias())
          scopesToRemove += instance.aliases.last.scope
        else {
          if (protocolledElements(assigneeType).instances.contains(instance)) {
            for (existingInstance <- protocolledElements(assigneeType).instances) {
              if (instance == existingInstance)
                existingInstance.aliases += Alias(assigneeName, assigneeScope)
            }
          }
          else {
            instance.aliases += Alias(assigneeName, assigneeScope)
            protocolledElements(assigneeType).instances += instance
          }
        }
      }
      for (scopeToRemove <- scopesToRemove) {
        removeAllAliasesInScope(scopeToRemove)
      }
    }


    /** Checks if the given expression is of the form x.y() where x is an existing alias and y is a method in
     *  its associated protocol.
     *
     * @param expr expression to check
     * @return true if expr is an alias calling a method in its protocol
     */
    def isAliasCallingProtocolMethod(expr: Trees#Tree):Boolean =  {
      expr match {
        case app@Apply(fun, args) =>
          methodTraverser.traverse(app)
          val methodCallInfos = methodTraverser.methodCallInfos
          for (methodCallInfo <- methodCallInfos) {
            getClosestScopeAliasInfo(methodCallInfo._2, methodCallInfo._3) match{
              case Some(aliasInfo) =>
                if(isProtocolMethod(methodCallInfo._1, methodCallInfo._3)) {
                  //reset the traverser's list to be empty
                  methodTraverser.methodCallInfos = ListBuffer[(String, String, String)]()
                  return true
                }
              case None =>
            }
          }
        case _ =>
      }
      //reset the traverser's list to be empty
      methodTraverser.methodCallInfos = ListBuffer[(String, String, String)]()
      false
    }

    /** Given a method name and element type, checks if the method is part of the protocol for the given type.
     *
     * @param methodName name of the method
     * @param elementType type of the element
     * @return true if the method is part of element's protocol
     */
    def isProtocolMethod(methodName: String, elementType:String): Boolean = {
      if(protocolledElements(elementType).methodToIndices.contains(methodName))
        return true
      false
    }

    /** Processes a match statement.
     *  First, checks if the expression being matched (expr) is an alias calling a method from its protocol.
     *  If so, goes through the function without updating the alias' state and then goes through the case statement.
     *  It will update the state when seeing which return value the function is expected to return
     *  If not, checks the expression and then the case statements
     *
     * @param expr expression being matched
     * @param cases case statements
     */
    def processMatchStatement(expr: Trees#Tree, cases: List[CaseDef])= {
      if(isAliasCallingProtocolMethod(expr)) {
        expr match{
          case func@Apply(Ident(functionName), args) =>
            val mapBeforeFunction = copyMap(protocolledElements)
            dealWithFunction(func, functionName, args)
            processCaseStatements(cases, mapBeforeFunction, expr)
          case func@Apply(Select(instanceCalledOn, functionName), args) =>
            val mapBeforeFunction = copyMap(protocolledElements)
            dealWithFunction(func, functionName, args, instanceCalledOn)
            processCaseStatements(cases, mapBeforeFunction, expr)
        }
      }
      else{
        checkInsideFunctionBody(expr)
        processCaseStatements(cases)
      }
    }

    /** Processes case statements
     *  Starts by saving the state of the instances before going through any case statements.
     *  It then uses those saved instances to go through all the case statements in turn, merging all the resulting
     *  instances at the end.
     *  For each case statement, if what is matched is a method from a protocol, transitions through
     *  the method:returnValue path in the protocol. (The return value being what is matched by the case).
     *
     * @param cases case statements to process
     * @param mapBeforeFunction Only defined if the expression is an alias calling a method in its protocol; lets
     *                          the update state function check if a function is mutating the state of the alias
     *                          differently than the protocol defines.
     * @param expr Only defined if the expression is an alias calling a method in its protocol. It is the said expression.
     */
    def processCaseStatements(cases: List[global.CaseDef], mapBeforeFunction:mutable.Map[String,ElementInfo]=null,
                              expr:Trees#Tree=null)= {
      val beforeCases = copyMap(protocolledElements)
      var mapsToMerge = ArrayBuffer[mutable.Map[String, ElementInfo]]()
      for(singleCase <- cases){
        protocolledElements = copyMap(beforeCases)
        if(expr != null){
          var returnValue = singleCase.pat.toString()
          if(returnValue.contains(".")) returnValue = returnValue.substring(returnValue.lastIndexOf('.')+1)
          updateStateIfNeeded(mapBeforeFunction, expr, ":"+returnValue)
        }
        checkInsideFunctionBody(singleCase.body)
        mapsToMerge += protocolledElements
      }
      while(mapsToMerge.nonEmpty){
        protocolledElements = mergeMaps(protocolledElements, mapsToMerge.last)
        mapsToMerge.remove(mapsToMerge.length-1)
      }
    }

    def assignScope(loopType: LoopType.Value) = {
      loopType match{
        case LoopType.forLoop =>
          currentScope.push("for")
        case LoopType.whileLoop =>
          currentScope.push("while")
        case LoopType.dowhileLoop =>
          currentScope.push("dowhile")
        case LoopType.trueLoop =>
          currentScope.push("true")
      }
    }



    /** Deals with any type of loop. The type of loop to be dealt with is defined in the loopType variable.
     *  Goes through the loop and at the end saves the state of the instances in instanceToInterimStates.
     *  It continues looping over the body of the loop until all the instances' states loop over, at which point
     *  we can confirm that the loop is not violating protocol.
     *  After this we need to merge the possible states the instances are in (since we don#t know how many times
     *  the loop executed) into the main map of instances.
     *
     * @param loopType enum of which loop type to deal with
     * @param loopContent content of the loop (its body)
     * @param generator optional generator of a for loop to deal with
     * @param cond optional condition to deal with (only while loops will have one)
     */
    def dealWithLoop(loopType: LoopType.Value, loopContent: Trees#Tree, generator: Seq[Trees#Tree]=null, cond: Trees#Tree=null) = {
      //initialise instanceToInterimStatesMap
      var instanceToInterimStates: mutable.HashMap[String, mutable.HashMap[Set[Alias], ListBuffer[Set[State]]]] = mutable.HashMap()
      for((elementType, elementInfo) <- protocolledElements){
        instanceToInterimStates += (elementType -> mutable.HashMap())
        if(loopType == LoopType.dowhileLoop || loopType == LoopType.trueLoop)
        //initialise the list to empty since these loops will always execute at least once so we don't want to keep the initial state
          for(instance <- elementInfo.instances) instanceToInterimStates(elementType) += instance.aliases -> ListBuffer()
        else
          for(instance <- elementInfo.instances)
            instanceToInterimStates(elementType) += instance.aliases -> ListBuffer(instance.currentStates)
      }
      if(loopType == LoopType.forLoop) checkInsideForLoopGenerator(generator) //only need to check this once
      //go through loop
      do {
        if(loopType == LoopType.whileLoop) checkInsideFunctionBody(cond)
        assignScope(loopType)
        checkInsideFunctionBody(loopContent)
        removeAllAliasesInScope(currentScope)
        currentScope.pop()
        if(loopType == LoopType.dowhileLoop) checkInsideFunctionBody(cond)
        updateMap(instanceToInterimStates)
      } while (!duplicatesInAllListsOfMap(instanceToInterimStates))
      if(loopType == LoopType.whileLoop)
        checkInsideFunctionBody(cond) //go through the while loop condition one more time after the body of the loop
      //assigns interim states to the instances
      assignInstancesWithLoopStates(instanceToInterimStates)
    }

    /** Updates the interim states map with the current instances
     *
     * @param instanceToInterimStates map with interim states in a loop
     */
    def updateMap(instanceToInterimStates: mutable.HashMap[String, mutable.HashMap[Set[Alias], ListBuffer[Set[State]]]]) = {
      for((elementType, elementInfo) <- protocolledElements){
        if(instanceToInterimStates.contains(elementType)){
          for (instance <- elementInfo.instances if instanceToInterimStates(elementType).contains(instance.aliases))
            instanceToInterimStates(elementType)(instance.aliases) += instance.currentStates
        }
      }
    }

    /** Assigns the interim states acquired while checking a loop to the global set of instances.
     *
     * @param instanceToInterimStates map with interim states in a loop
     */
    def assignInstancesWithLoopStates(instanceToInterimStates: mutable.HashMap[String, mutable.HashMap[Set[Alias], ListBuffer[Set[State]]]]) = {
      for(elementType <- instanceToInterimStates.keys)
        for((savedAliases, listOfSetOfStates) <- instanceToInterimStates(elementType))
          for(instance <- protocolledElements(elementType).instances if instance.aliases == savedAliases) {
            for(setOfStates <- listOfSetOfStates)
              instance.currentStates = setOfStates ++ instance.currentStates
          }
    }

    /** Checks the code inside a for loop generator
     *
     * @param generator the for loop generator
     */
    def checkInsideForLoopGenerator(generator: Seq[Trees#Tree])= {
      generator match {
        case List(fq"$pat <- $beginning until $end") =>
          for (begin <- beginning.children)
            checkInsideFunctionBody(begin)
          checkInsideFunctionBody(end)
        case List(fq"$pat <- $beginning to $end") =>
          for (begin <- beginning.children)
            checkInsideFunctionBody(begin)
          checkInsideFunctionBody(end)
        case List(fq"$pat <- $gen") =>
          checkInsideFunctionBody(gen)
        case _ => checkInsideObjectBody(generator)
      }
    }

    /** Handles try-catch in a basic manner, assuming no exceptions.
     * Just goes through try then finally bodies.
     *
     * @param tryBody     Code inside the try block.
     * @param finallyBody Code inside the finally block.
     * @return what is returned from the finally block, if any
     */
    def checkTryCatchFinally(tryBody: Trees#Tree, finallyBody: Trees#Tree): Option[Set[Instance]] = {
      checkInsideFunctionBody(tryBody)
      checkInsideFunctionBody(finallyBody)
    }

    /** Handles an if-else statement.
     *  Saves the current state of instances in beforeMap, then
     *  goes through both paths and gets new states for the instances.
     *  Once this is done it merges the two possible set of instances (afterIfMap and protocolledElements)
     *  and protocolledElements then hold all possible states they could be in after going through either path.
     *
     * @param condition Condition inside the if statement
     * @param ifBody    Code inside the if block
     * @param elseBody  Code inside the else block
     * @return What is returned from the if-else statement
     */
    def dealWithIfElse(condition: Trees#Tree, ifBody: Trees#Tree, elseBody: Trees#Tree): Option[Set[Instance]] = {
      checkInsideFunctionBody(condition)
      val beforeMap = copyMap(protocolledElements)
      val returnedIfOption = checkInsideFunctionBody(ifBody)
      val afterIfMap = copyMap(protocolledElements)
      var returnedIf: Set[Instance] = Set()
      returnedIfOption match {
        case Some(returnedIfValue) =>
          returnedIf = returnedIfValue
        case None =>
      }
      protocolledElements = beforeMap
      val returnedElseOption = checkInsideFunctionBody(elseBody)
      var returnedElse: Set[Instance] = Set()
      returnedElseOption match {
        case Some(returnedElseValue) =>
          returnedElse = returnedElseValue
        case None =>
      }
      val returnedIfElse = Option(returnedIf ++ returnedElse)
      protocolledElements = mergeMaps(afterIfMap, protocolledElements) //at this point, protocolledElements is the map after going through just else
      returnedIfElse
    }

    /** Merges two instance maps together into one map with instances present in both maps having merges states
     *
     * @param firstMap first map to merge, order is not important
     * @param secondMap second map to merge, order is not important
     */
    def mergeMaps(firstMap: mutable.Map[String, ElementInfo], secondMap: mutable.Map[String, ElementInfo]): mutable.Map[String, ElementInfo] ={
      var newMap = mutable.Map[String, ElementInfo]()
      for((elementType, elementInfo) <- firstMap){
        if(secondMap.contains(elementType)) {
          newMap += (elementType -> elementInfo)
          newMap(elementType).instances = mergeInstanceStates(elementInfo.instances, secondMap(elementType).instances)
        }
        else
          newMap += (elementType -> elementInfo)
      }
      for((elementType, elementInfo) <- secondMap if !newMap.contains(elementType))
        newMap += (elementType -> elementInfo)
      newMap
    }


    /** Gets the object which has scope which is closest to the current scope.
     *  It goes through the scope, taking it a level down each time, checking if an object with the given type has
     *  the scope until a match is found, or the scope is empty (no object could be found).
     *
     * @param objectType type of the object to find
     * @return the object found, if any is found
     */
    def getClosestScopeObject(objectType: String): Option[Element] = {
      val classesAndObjects = ElementTraverser.elements
      if (classesAndObjects.isEmpty) return None
      val curScope = currentScope.clone()
      while (curScope.nonEmpty) {
        for (element <- classesAndObjects) {
          if (element.elementType == objectType && element.scope == curScope && element.isObject) {
            return Option(element)
          }
        }
        curScope.pop()
      }
      None
    }

    /** Checks if the object (calledOn) is defined in the file and if it has not already been initialised, runs the
     * code inside the object.
     *
     * @param calledOn  The object that the method is called on and the one we want to check the constructor for if
     *                  that hasn't been done already
     */
    def checkObjectFunctionCall(calledOn: global.Tree): Unit = {
      if (calledOn == null) return
      val calledOnType = calledOn.tpe.toString()
      for (element <- ElementTraverser.elements
           if (!element.initialised && element.isObject && element.elementType == calledOnType
             && element.scope == getScope(calledOn))) {
        element.initialised = true
        currentScope.push(calledOn.toString())
        checkInsideObjectBody(element.body)
        currentScope.pop()
      }
    }

    /** Checks if the function given is an assignment function.
     *  If it is, checks the assignment and returns true.
     *  If not, returns false
     *
     * @param function function to check
     * @return true if function is an assignment
     */
    def isAssignmentFunction(function: global.Apply): Boolean = {
      function match {
        case Apply(Select(arg1, TermName(functionName)), List(arg3)) =>
          val regex = ".*_\\$eq".r
          regex.findPrefixMatchOf(functionName) match {
            case Some(mat) =>
              val value = mat.toString
              processAssignment(value.substring(0, value.length - 4), arg3.tpe.toString(),arg3)
              return true
            case None =>
          }
        case _ =>
      }
      false
    }

    /** Assign the args to the parameters.
     *  Works by equating to actual assignment statements as such:
     *  val parameter = arg
     *  This lets us associate the parameter from the function to the pre-existing argument given.
     *
     *
     * @param parameters bits in the function definition
     * @param args bits passed into the function
     * @param calledOn what the function is called on (in x.y(), x)
     */
    def assignParameters(parameters: ArrayBuffer[(String, String)], args: List[Tree], calledOn: Tree) = {
      if (calledOn != null && protocolledElements.contains(calledOn.symbol.tpe.toString())) {
        println("inside if")
        processNovelAssignment(getSimpleClassName(calledOn.symbol.tpe.toString()), calledOn.symbol.tpe.toString(), calledOn)
      }
      var i = 0
      for (param <- parameters) {
        if (protocolledElements.contains(param._2)) {
          processNovelAssignment(param._1, param._2, args(i))
        }
        i += 1
      }
    }

    /** Checks all the arguments given
     *
     * @param args arguments given
     */
    def checkArguments(args: List[global.Tree]) = {
      for (arg <- args) {
        checkInsideFunctionBody(arg)
      }
    }

    /** Checks if the cache contains the entry
     *
     * @param cache map with ((elementType, parameterNames, states) -> nextStates) entries
     * @param entry entry of (elementType, parameterNames, states)
     * @return
     */
    def cacheContainsEntry(cache: Map[ArrayBuffer[(String, Set[String], Set[State])], ArrayBuffer[Set[State]]],
                           entry: ArrayBuffer[(String, Set[String], Set[State])]):
    (Boolean, ArrayBuffer[(String, Set[String], Set[State])]) = {
      for ((array, states) <- cache) {
        val set1 = array.toSet
        val set2 = entry.toSet
        if (set1.equals(set2)) return (true, array)
      }
      (false, null)
    }

    /** Create a cache entry from the parameters given in the givenToFunctionParams.
     *  For each of the parameters of the function, collects the states the instances are in and adds those to the entry.
     *
     * @param givenToFunctionParams
     * @return cache entry as an array of (elementType, parameterNames, states)
     */
    def createCacheEntry(givenToFunctionParams: mutable.HashMap[String, mutable.HashMap[(String, mutable.Stack[String]),
      Set[(String, mutable.Stack[String])]]]): ArrayBuffer[(String, Set[String], Set[State])] = {
      val cacheEntry = ArrayBuffer[(String, Set[String], Set[State])]()
      for(elementType <- givenToFunctionParams.keys) {
        for ((aliasInfo, paramInfos) <- givenToFunctionParams(elementType)) {
          var currentStates = Set[State]()
          for (paramInfo <- paramInfos) {
            val instancesToGetStatesFrom =
              protocolledElements(elementType).instances.filter(instance => instance.containsAliasInfo(paramInfo._1, paramInfo._2))
            for (instance <- instancesToGetStatesFrom) {
              currentStates ++= instance.currentStates
            }
          }
          var paramNames = Set[String]()
          for (paramInfo <- paramInfos) paramNames += paramInfo._1
          cacheEntry.append((elementType, paramNames, currentStates))
        }
      }
      cacheEntry
    }

    /** Puts the instances into new states according to the cache of the function
     *
     * @param parameters parameters to change the state of
     * @param function function who's cache is used
     */
    def mutateInstances(parameters: ArrayBuffer[(String, Set[String], Set[State])], function: Function) = {
      var i = 0
      for ((elementType, parameterNames, states) <- parameters) {
        for (paramName <- parameterNames) {
          getClosestScopeAliasInfo(paramName, elementType) match {
            case Some(paramInfo) =>
              val instancesToMutate =
                protocolledElements(elementType).instances.filter(instance => instance.containsAliasInfo(paramInfo._1, paramInfo._2))
              for (instance <- instancesToMutate) instance.currentStates = function.stateCache(parameters)(i)
            case None =>
          }
        }
        i += 1
      }
    }

    /** Get current states of instances in the cache and return them in an appropriate format to be able to add to the
     *  cache as nextStates
     *
     * @param cacheEntry entry to update
     * @return array of nextStates to add to the cache entry
     */
    def findNextStates(cacheEntry: ArrayBuffer[(String, Set[String], Set[State])]): ArrayBuffer[Set[State]] = {
      val nextStatesArray = ArrayBuffer[Set[State]]()
      for ((elementType, parameterNames, states) <- cacheEntry) {
        var nextStates = Set[State]()
        for (paramName <- parameterNames) {
          getClosestScopeAliasInfo(paramName, elementType) match {
            case Some(paramInfo) =>
              val instancesWithNextStates =
                protocolledElements(elementType).instances.filter(instance => instance.containsAliasInfo(paramInfo._1, paramInfo._2))
              for (instance <- instancesWithNextStates) nextStates ++= instance.currentStates
            case None =>
          }
        }
        nextStatesArray.append(nextStates)
      }
      nextStatesArray
    }

    /** Checks if two lists of parameters, formatted differently, are the same
     *
     * @param firstParameters ArrayBuffer of parameters as arrays with form [(type, name)]
     * @param secondParameters List of parameters in tree form
     * @return true if the parameters match
     */
    def typesMatch(firstParameters:ArrayBuffer[(String, String)], secondParameters:List[global.Tree]): Boolean ={
      if(!firstParameters.exists(param => param._1.length > 0) && secondParameters.isEmpty) return true //both lists are empty
      for((param, i) <- firstParameters.zipWithIndex) {
        if(param._2 != secondParameters(i).tpe.toString())
          return false
      }
      true
    }

    /** Checks function calls.
     * First it checks if the function is an assignment in which case it just returns to let the assignment
     * be dealt with in the assignment function
     * Then it checks if the function is new x and therefore the code inside a class should be analysed.
     * Then it checks if an object is being called on for the first time and its code should be analysed.
     * Then it goes to analyse inside the function body, renaming the instances to parameter names if needed.
     *
     * @param func function to deal with
     * @param functionName name of the function
     * @param args function arguments (passed to it)
     * @param calledOn optional, what the function is called on (in x.y(), x)
     * @return what is returned from the function, if applicable
     */
    def dealWithFunction(func: global.Apply, functionName: global.Name, args: List[global.Tree], calledOn: Tree = null):
                          Option[Set[Instance]] = {
      //region <Checks>

      //check for an assignment function, don't want to check args or do anything else in this case
      if (isAssignmentFunction(func)) return None

      //checks parameters
      checkArguments(args)

      //checks for "new Class()" constructor function
      val isCurrentIsNewAndReturned = checkNewFunction(func, args)
      if (isCurrentIsNewAndReturned._2) {
        if (isCurrentIsNewAndReturned._1)
          return Some(isCurrentIsNewAndReturned._3)
        else return None
      }
      //checks for Object constructor call
      checkObjectFunctionCall(calledOn)
      //endregion
      //finding function definition
      for (function <- functionTraverser.functions
           if (function.name == functionName.toString()
             && function.scope == getScope(func, dontCheckSymbolField = true)
             && typesMatch(function.params, args))) {
        currentScope.push(function.name) //push scope so parameters will be scoped inside the function
        println(s"before assigning parameters to method ${function.name}, instances are "+protocolledElements)
        assignParameters(function.params, args, calledOn)
        println(s"after assigning parameters to method ${function.name}, instances are "+protocolledElements)
        val cacheEntry = dealWithCache(function)
        if(cacheEntry == null) {
          println("returning "+function.returned)
          return function.returned
        }
        //check inside the function body
        currentScope.push("body")
        println("function body is "+function.body)
        val returned = checkInsideFunctionBody(function.body)
        println("after checking function, instances are "+protocolledElements)
        println("returned is "+returned)
        //figuring out what is returned PUT THIS INTO ITS OWN FUNCTION
        returned match {
          case Some(setOfInstances) =>
            val scopeClone = currentScope.clone()
            var instancesReturned = setOfInstances ++ Set(Instance(Set(Alias("scope+", scopeClone.clone())), Set())) //delete internal variables
            scopeClone.pop()
            instancesReturned = instancesReturned ++ Set(Instance(Set(Alias("scope+", scopeClone)), Set())) //need to delete the parameters too
            function.returned = Some(copyInstances(instancesReturned))
          case null =>
            function.returned = null
          case None =>
            function.returned = Some(Set(Instance(Set(Alias("scope+", currentScope.clone())), Set())))

        }
        //remove aliases inside the body of the function since they can't be used anymore
        removeAllAliasesInScope(currentScope)
        currentScope.pop()
        //update cache
        function.stateCache += cacheEntry -> findNextStates(cacheEntry)
        //delete aliases defined in the function
        removeAllAliasesInScope(currentScope)
        currentScope.pop()
        return function.returned
      }
      None
    }

    /** Check cache for hit with the current instances.
     *  If there is a hit with next states then change the states and return null, indicating that the
     *  dealWithFunction function should return.
     *  If there is a hit with null then we are in a recursive function and should thus skip the next call and
     *  return null
     *  If there is no hit, create a new cache entry and return it
     *
     * @param function function to check the cache of
     * @return either a new entry for the cache or null if we should leave the function as a result of a cache hit
     */
    def dealWithCache(function: Function):(ArrayBuffer[(String,Set[String], Set[State])]) = {
      //create map
      val givenToFunctionParams= createMap(function.params)
      //make possible cache entry
      val cacheEntry = createCacheEntry(givenToFunctionParams)
      //check if it hits
      val cacheHitAndParams = cacheContainsEntry(function.stateCache, cacheEntry)
      val cacheHit = cacheHitAndParams._1
      val parameters = cacheHitAndParams._2
      //mutate state if possible and skip recursive call if needed
      if (cacheHit && function.stateCache(parameters) != null) {
        mutateInstances(parameters, function)
        removeAllAliasesInScope(currentScope)
        println("cached result, skipping")
        currentScope.pop()
        return null
      }
      if (cacheHit && function.stateCache(parameters) == null) {
        removeAllAliasesInScope(currentScope)
        println("recursing")
        currentScope.pop()
        return null
      }
      //if it doesn't hit, put in a null entry
      function.stateCache += cacheEntry -> null
      cacheEntry
    }

    /** Checks if the object given has been seen before. If not, executes the code inside it.
     *
     * @param objectName object to check
     */
    def checkObject(objectType: String, objectName: String) = {
      getClosestScopeObject(objectType) match {
        case Some(obj) =>
          if(!obj.initialised) {
            obj.initialised = true
            currentScope.push(objectName)
            checkInsideObjectBody(obj.body)
            currentScope.pop()
          }
        case _ =>
      }
    }


    /** Checks for a new x function and executes the code within the class if found. Renames instances to
     *  constructor parameter names if needed.
     *
     * @param function function to check
     * @param args possible constructor arguments for the function
     * @return 1:true if the
     */
    def checkNewFunction(function: global.Apply, args: List[Tree]): (Boolean, Boolean, Set[Instance]) = {
      var isCurrentType = false
      var returned: Set[Instance] = Set()
      function match {
        case q"new { ..$earlydefns } with ..$parents { $self => ..$stats }" =>
          parents match {
            case List(item) =>
              //note here getScope is called on the symbol owner of the function since we want to skip the class name in the scope
              val IsCurrentTypeAndReturned =
                checkInsideClass(getScope(function.symbol.owner), function.tpe.toString(), args)
              isCurrentType = IsCurrentTypeAndReturned._1
              returned = IsCurrentTypeAndReturned._2
            case List(Apply(className, arg2)) =>
              val IsCurrentTypeAndReturned = checkInsideClass(getScope(className), className.tpe.toString(), args)
              isCurrentType = IsCurrentTypeAndReturned._1
              returned = IsCurrentTypeAndReturned._2
            case _ =>
          }
          return (isCurrentType, true, returned)
        case _ =>
      }
      (false, false, returned)
    }

    def getSimpleClassName(elementType: String): String = {
      if(elementType.contains("."))
        elementType.substring(elementType.lastIndexOf(".")+1)
      else elementType
    }

    /** Checks inside a class body
     *
     * @param scope scope of the class
     * @param elementType type of the class
     * @param args constructor arguments
     * @return
     */
    def checkInsideClass(scope: mutable.Stack[String], elementType: String, args: List[Tree]): (Boolean, Set[Instance]) = {
      var returned: Set[Instance] = Set()
      for (element <- ElementTraverser.elements
           if !element.isObject && element.elementType == elementType && element.scope == scope) {
        println(element.params)
        println(args)
        assignParameters(element.params, args, null)
        println("after assigning parameters, instances are "+protocolledElements)
        currentScope.push(elementType)
        if(protocolledElements.contains(elementType))
          protocolledElements(elementType).instances +=
            Instance(Set(Alias(getSimpleClassName(elementType), currentScope.clone())), Set(protocolledElements(elementType).states(0)))
        checkInsideObjectBody(element.body)
        val scopeInstance = Instance(Set(Alias("scope+", currentScope.clone())), Set())
        if(protocolledElements.contains(elementType)) {
          for (instance <- protocolledElements(elementType).instances) {
            if (instance.containsAliasInfo(getSimpleClassName(elementType), currentScope))
              returned = Set(Instance(instance.aliases, instance.currentStates), scopeInstance)
          }
        }
        removeAllAliasesInScope(currentScope)
        currentScope.pop()
      }
      (protocolledElements.contains(elementType), returned)
    }

    /** For the parameter list given, checks if they match any of our defined instances. If so, renames the instance
     * to the parameter name. Keeps memory of the renaming in a hashmap of parameter name to instance name so these
     * can easily be renamed after the function exits.
     *
     * @return
     */
    def createMap(parameters: ArrayBuffer[(String, String)]):
    mutable.HashMap[String,mutable.HashMap[(String, mutable.Stack[String]), Set[(String, mutable.Stack[String])]]] = {
      val givenToFunctionParam = new mutable.HashMap[String, mutable.HashMap[(String, mutable.Stack[String]), Set[(String, mutable.Stack[String])]]]()
      for(elementType <- protocolledElements.keys)
        givenToFunctionParam += (elementType -> mutable.HashMap())
      var argCounter = 0
      val paramScope = currentScope.clone()
      for (arg <- parameters) {
        val argName = arg._1
        getClosestScopeAliasInfo(argName, arg._2) match {
          case Some(aliasInfo) =>
            val paramName = parameters(argCounter)._1
            givenToFunctionParam(arg._2).get(aliasInfo._1, aliasInfo._2) match {
              case Some(setOfParams) =>
                val updatedSet = setOfParams ++ Set((paramName, paramScope))
                givenToFunctionParam(arg._2) += (aliasInfo._1, aliasInfo._2) -> updatedSet
              case None =>
                givenToFunctionParam(arg._2) += (aliasInfo._1, aliasInfo._2) -> Set((paramName, paramScope))
            }
          case None =>
        }
        argCounter += 1
      }
      givenToFunctionParam
    }

    def checkForInconsistentStateMutation(instancesToUpdate: Set[Instance], originalInstances: Set[Instance],
                                          aliasInfo:(String, mutable.Stack[String]), aliasType:String,
                                          methodName:String, line:Trees#Tree) = {
      for (instance <- originalInstances) {
        updateInstance(instance, methodName, line, protocolledElements(aliasType), aliasType)
      }
      if (!(instancesToUpdate == originalInstances)) {
        var expectedStates:Set[State] = Set()
        for(instance <- originalInstances){
          expectedStates ++= instance.currentStates
        }
        var actualStates:Set[State] = Set()
        for(instance <- instancesToUpdate){
          actualStates ++= instance.currentStates
        }

        throw new inconsistentStateMutation(methodName, aliasInfo._1,
          line.pos.source.toString(), line.pos.line, expectedStates, actualStates)
      }
    }

    /** For a given line of code, checks if it is a method on an instance with protocol and if so updates its state
     *
     * @param line
     */
    def updateStateIfNeeded(mapBeforeFunction: mutable.Map[String, ElementInfo], line: Trees#Tree, returnValue:String="") = {
      println("CALLED UPDATE")
      println("protocolled elements are "+protocolledElements)
      line match {
        case app@Apply(fun, args) =>
          methodTraverser.traverse(app)
        case _ =>
      }
      val methodCallInfos = methodTraverser.methodCallInfos
      for (methodCallInfo <- methodCallInfos) {
        var methodName = methodCallInfo._1+returnValue
        if(methodName.contains(".") && methodName.contains("(")) {
          methodName = methodName.substring(0,methodName.indexOf("(")+1) + methodName.substring(methodName.lastIndexOf(".")+1)
        }
        println("method name is "+methodName)
        val aliasName = methodCallInfo._2
        val aliasType = methodCallInfo._3
        breakable {
          getClosestScopeAliasInfo(aliasName, aliasType) match {
            case Some(aliasInfo) =>
              val currentElementInfo = protocolledElements(aliasType)
              if (!currentElementInfo.methodToIndices.contains(methodName) && !currentElementInfo.returnValueToIndice.contains(methodName)) {
                if(!methodName.contains(":") || !currentElementInfo.methodToIndices.contains(methodName.substring(0, methodName.indexOf(":"))))
                  break
              }
              //checks if there is an inconsistent state mutation (FUNCTION)
              val instancesToUpdate =
                currentElementInfo.instances.filter(instance => instance.containsAliasInfo(aliasInfo._1, aliasInfo._2))
              val originalInstances =
                mapBeforeFunction(aliasType).instances.filter(instance => instance.containsAliasInfo(aliasInfo._1, aliasInfo._2))
              if (!(instancesToUpdate == originalInstances)) {
                checkForInconsistentStateMutation(instancesToUpdate, originalInstances, aliasInfo, aliasType, methodName, line)
              }
              else {
                for (instance <- instancesToUpdate) {
                  updateInstance(instance, methodName, line, currentElementInfo, aliasType)
                }
              }
            case None =>
              println("not getting an alias")
          }
        }
      }
      //reset the traverser's list to be empty
      methodTraverser.methodCallInfos = ListBuffer[(String, String, String)]()
    }

    def getPossibleMethods(elementType: String, states: Set[State]):Set[ReturnValue] = {
      var possibleMethods = protocolledElements(elementType).stateToAvailableMethods.values.last
      println("state map is "+protocolledElements(elementType).stateToAvailableMethods)
      for(state <- states) {
        possibleMethods = possibleMethods.intersect(protocolledElements(elementType).stateToAvailableMethods(state))
      }
      possibleMethods
    }

    def updateInstance(instance: Instance, methodName: String, line: Trees#Tree, currentElementInfo: ElementInfo, elementType:String) = {
      var newSetOfStates: Set[State] = Set()
      for (state <- instance.currentStates) {
        if(state.name == Undefined)
          throw new usedUninitialisedException(methodName, sortSet(instance.getAliasNames()), elementType, line.pos.line)
        println("found method name " + methodName)
        var indexSet:Set[Int] = Set()
        if(currentElementInfo.methodToIndices.contains(methodName))
          indexSet = currentElementInfo.methodToIndices(methodName)
        else if(currentElementInfo.returnValueToIndice.contains(methodName))
          indexSet = Set(currentElementInfo.returnValueToIndice(methodName))
        else {
          val rawMethodName = methodName.substring(0, methodName.indexOf(":"))
          indexSet = Set(currentElementInfo.methodToIndices(rawMethodName).min)
        }
        var newStates: Set[State] = Set[State]()
        newStates += currentElementInfo.transitions(state.index)(indexSet.min)
        if (indexSet.size > 1 && currentElementInfo.transitions(state.index)(indexSet.min).name == Undefined)
          newStates = for (x <- indexSet - indexSet.min) yield currentElementInfo.transitions(state.index)(x)
        println("new states are " + newStates)
        for (state <- newStates if state.name == Undefined) {
          val possibleNextMethods = getPossibleMethods(elementType, instance.currentStates)
          throw new protocolViolatedException(sortSet(instance.getAliasNames()), elementType,
            sortSet(instance.currentStates), methodName, line.pos.source.toString(), line.pos.line,
            formatMethods(sortSet(possibleNextMethods)))
        }
        newSetOfStates = newSetOfStates ++ newStates
      }
      instance.currentStates = newSetOfStates
    }

    def formatMethods(methods:SortedSet[ReturnValue]): String={
      var formattedMethods = ""
      for(method <- methods){
        if(method.valueName != Any)
          formattedMethods += method.parentMethod.name + ":" + method.valueName + " "
        else formattedMethods += method.parentMethod.name + " "

      }
      if(formattedMethods == "")
        formattedMethods = "No methods are available in this state."
      formattedMethods
    }

    //region<Traversers>
    /** Traverses a tree and collects (methodName, aliasName) from method application statements
     *
     */
    object methodTraverser extends Traverser {
      var methodCallInfos = ListBuffer[(String, String, String)]() //methodName, calledOnName, calledOnType
      override def traverse(tree: Tree): Unit = {
        tree match {
          case app@Apply(fun, args) =>
            app match {
              case q"$expr(...$exprss)" =>
                expr match {
                  case select@Select(qualifier, name) =>
                    var instanceName = qualifier.toString()
                    var instanceType = qualifier.tpe
                    if(qualifier.hasSymbolField)
                      instanceType = qualifier.symbol.tpe
                    if (qualifier.hasSymbolField) instanceName = qualifier.symbol.name.toString()
                    val methodCallInfo = (/*_*/ name.toString()+ getParametersFromTree(exprss) /*_*/ ,
                      instanceName, instanceType.toString())
                    methodCallInfos += methodCallInfo
                  case _ =>
                }
              case _ =>
            }
            super.traverse(fun)
            super.traverseTrees(args)
          case _ =>
            super.traverse(tree)
        }
      }
    }


    /** Traverses a tree and collects elements found inside "elements" */
    object ElementTraverser extends Traverser {
      var elements = ListBuffer[Element]()
      override def traverse(tree: Tree): Unit = {
        tree match {
          case obj@q"$mods object $tname extends { ..$earlydefns } with ..$parents { $self => ..$body }" =>
            elements += Element(obj.symbol.tpe.toString(), ArrayBuffer(), body, getScope(obj), isObject = true)
            addElementProtocolIfItExists(body, obj.symbol.tpe.toString(), tree, objectName = tname.toString())
            super.traverse(obj)
          case clas@q"$mods class $tpname[..$tparams] $ctorMods(...$paramss) extends { ..$earlydefns } with ..$parents { $self => ..$body }" =>
            val parameters = /*_*/ getParameters(paramss) /*_*/
            elements += Element(clas.symbol.tpe.toString(), parameters, body, getScope(clas))
            addElementProtocolIfItExists(body, clas.symbol.tpe.toString(), tree)
            super.traverse(clas)
          case _ =>
            super.traverse(tree)
        }
      }
    }

    /** Gathers function informations in the code inside "functions" */
    object functionTraverser extends Traverser {
      var functions = ListBuffer[Function]()
      override def traverse(tree: Tree): Unit = {
        tree match {
          case func@q"$mods def $funcName[..$tparams](...$paramss): $returnType = $body" =>
            val parameters = /*_*/ getParameters(paramss) /*_*/
            if (funcName.toString() != "<init>") //ignore constructors here as they will be dealt with better later
              functions +=
                Function(funcName.toString(), parameters, returnType, body, getScope(func),
                  Map[ArrayBuffer[(String, Set[String], Set[State])], ArrayBuffer[Set[State]]](), returned=None)
            /*_*/ super.traverse(body) /*_*/
          case _ =>
            super.traverse(tree)
        }
      }
    }


    //endregion

    def isTypestateAnnotation(annotations: List[global.AnnotationInfo]): (Boolean, String) = {
      for (annotation@AnnotationInfo(arg1, arg2, arg3) <- annotations) {
        getFilenameFromTypestateAnnotation(annotation) match {
          case Some(protocolName) =>
            return (true, protocolName)
          case None =>
            return (false, "")
        }
      }
      (false, "")
    }

    /** Checks whether the object or class is following its protocol in the code.
     * It first checks if the element has a typestate annotation, then runs the protocol and collects the information
     * from it.
     * Then it checks the methods in the protocol are a subset of those defined in the element.
     * Then it checks the protocol is followed
     *
     * @param body     The code to check
     * @param elementType     Name of the element
     * @param tree     The entire code
     * @param objectName If this is an object, then the name of the object, otherwise: null
     * @return
     */
    def addElementProtocolIfItExists(body: Seq[Trees#Tree], elementType: String,
                                     tree: Tree, objectName:String=null)= {
      if(isTypestateAnnotation(tree.symbol.annotations)._1) {
        val protocolName = isTypestateAnnotation(tree.symbol.annotations)._2
        //retrieve the serialized data
        val (stateMachine, states, returnValuesArray) = getDataFromProtocol(protocolName)
        val methodToIndices = createMethodToIndicesMap(returnValuesArray)
        val returnValueStringToIndice = createReturnValueStringToIndiceMap(returnValuesArray)
        val returnValueToIndice = createReturnValueToIndiceMap(returnValuesArray)
        val stateToAvailableMethods = createStateToAvailableMethodsMap(stateMachine, returnValueToIndice, states) //could use in error message to tell users what methods are available in the current state
        checkProtocolMethodsSubsetElementMethods(methodToIndices.keys.toArray, body, elementType, protocolName)
        val currentElementInfo = ElementInfo(stateMachine, states, methodToIndices, returnValueStringToIndice,
          stateToAvailableMethods, Set[Instance]())
        if (objectName != null)
          currentElementInfo.objectName = objectName
        protocolledElements += elementType -> currentElementInfo
        savedBreakInstances += elementType -> mutable.Map()
        println("map with protocolled elements is " + protocolledElements)
      }
    }

    /** Gets the scope of a tree from its symbol.
     * It will check if the tree has a symbol field unless specified not to.
     * The boolean value is there because some trees will not pass the hasSymbolField check even though it is
     * possible to get their scope in this way. I don't know why.
     *
     * @param obj
     * @param dontCheckSymbolField
     * @return
     */
    def getScope(obj: Tree, dontCheckSymbolField: Boolean = false): mutable.Stack[String] = {
      val objectScope = mutable.Stack[String]()
      if (obj.hasSymbolField || dontCheckSymbolField) {
        for (symbol <- obj.symbol.owner.ownerChain.reverse)
          objectScope.push(symbol.name.toString())
      }
      objectScope
    }

    /** Gets the scope of a symbol by looking through its owner chain
     *
     * @param symbol
     * @return mutable stack representing the scope of the given symbol
     */
    def getScope(symbol: Symbol): mutable.Stack[String] = {
      val objectScope = mutable.Stack[String]()
      for (symbol <- symbol.owner.ownerChain.reverse)
        objectScope.push(symbol.name.toString())
      objectScope
    }

    /** Gets a parameter string as formatted in a function definition from a tree of them */
    def getParametersFromTree(params: List[List[Tree]]): String = {
      params match {
        case List(List()) => "()"
        case List(List(value)) => keepOnlyName(value.tpe.toString()).mkString("(", "", ")")
        case List(values) =>
          var parameters: ArrayBuffer[String] = ArrayBuffer()
          for (elem <- values) {
            parameters += keepOnlyName(elem.tpe.toString)
          }
          parameters.mkString("(", ",", ")")
        case _ => ""
      }
    }

    /** Checks that methods in the protocol are a subset of those in the body of the element
     *
     * @param rawProtocolMethods : names of the methods in the protocol
     * @param elementBody : body of the class or object being checked
     * @param filename : name of the file the user code is in to generate a useful error message
     */
    def checkProtocolMethodsSubsetElementMethods(rawProtocolMethods: Array[String], elementBody: Seq[Trees#Tree],
                                                 elementType: String, filename: String): Unit = {
      val elementMethods = getMethodNames(elementBody)
      val protocolMethods =
        for (method <- rawProtocolMethods) yield stripReturnValue(method.replaceAll("\\s", ""))
      if (!(protocolMethods.toSet subsetOf elementMethods)) throw new badlyDefinedProtocolException(
        s"Methods ${protocolMethods.toSet} defined in $filename are not a subset of methods " +
          s"$elementMethods defined in class $elementType. Methods ${protocolMethods.toSet.diff(elementMethods)} are defined in " +
          s"the protocol but not in the class")
    }


    /** Checks if an annotation is a Typestate annotation and returns the filename if so
     *
     * @param annotation
     * @return
     */
    def getFilenameFromTypestateAnnotation(annotation: AnnotationInfo): Option[String] = {
      annotation match {
        case AnnotationInfo(arg1, arg2, arg3) =>
          if (arg1.toString == "Typestate" || arg1.toString == "compilerPlugin.Typestate") {
            Some(arg2.head.toString().stripSuffix(""""""").stripPrefix("""""""))
          }
          else None
        case _ => None
      }
    }

    /** Returns a set of method names (as name(parameters)) from the body of a class
     *
     * @param methodBody
     * @return
     */
    def getMethodNames(methodBody: Seq[Trees#Tree]): Set[String] = {
      var methodNames: Set[String] = Set()
      for (line <- methodBody) {
        line match {
          case q"$mods def $tname[..$tparams](...$paramss): $tpt = $expr" =>
            val parameters = /*_*/ getParameterTypes(paramss) /*_*/
            methodNames += tname + s"($parameters)"
          case _ =>
        }
      }
      methodNames
    }

    /** Returns a string of the parameters given
     *
     * @param params
     * @return
     */
    def getParameterTypes(params: List[List[ValDef]]): String = {
      params match {
        case List(List()) => ""
        case List(List(value)) =>
          var valueName = value.tpt.toString()
          if(valueName.contains('.'))
            valueName = valueName.substring(valueName.lastIndexOf('.')+1)
          valueName
        case List(values) =>
          var parameters: ArrayBuffer[String] = ArrayBuffer()
          for (elem <- values) {
            var valueName = elem.tpt.toString
            if(valueName.contains('.'))
              valueName = valueName.substring(valueName.lastIndexOf('.')+1)
            parameters += valueName
          }
          parameters.mkString(",")
        case _ => ""
      }
    }


    /** Gets parameters from a tree as their name and type in a string array */
    def getParameters(params: List[List[ValDef]]): ArrayBuffer[(String, String)] = {
      params match {
        case List(List()) => ArrayBuffer(("", null))
        case List(List(value)) =>
          ArrayBuffer((value.name.toString(), value.tpt.toString()))
        case List(values) =>
          var parameters: ArrayBuffer[(String, String)] = ArrayBuffer()
          for (elem <- values) {
            parameters += ((elem.name.toString(), elem.symbol.tpe.toString()))
          }
          parameters
        case _ => ArrayBuffer()
      }
    }


    /** Checks to see if there are duplicates in all the lists of a map(Instance -> list) */
    def duplicatesInAllListsOfMap(maps:mutable.HashMap[String, mutable.HashMap[Set[Alias], ListBuffer[Set[State]]]]):Boolean={
      for(map <- maps.values)
        for((instance, list) <- map) for((instance, list) <- map if list.diff(list.distinct).isEmpty) return false
      true
    }

    def copyMap(map: mutable.Map[String, ElementInfo]): mutable.Map[String, ElementInfo] ={
      var copiedMap = mutable.Map[String, ElementInfo]()
      for((elementType, elementInfo) <- map){
        copiedMap += (elementType -> ElementInfo(elementInfo.transitions, elementInfo.states,
          elementInfo.methodToIndices, elementInfo.returnValueToIndice, elementInfo.stateToAvailableMethods,
          copyInstances(elementInfo.instances)))
      }
      copiedMap
    }


    def removeAllAliasesInScope(scope:mutable.Stack[String]) = {
      breakable {
        if (scope == null) break()
        for ((elementType, elementInfo) <- protocolledElements) {
          for (instance <- elementInfo.instances) {
            for (alias <- instance.aliases) {
              if (alias.scope == scope)
                instance.aliases -= alias
            }
          }
          protocolledElements(elementType).instances = cleanInstances(elementInfo.instances)
        }
        println(s"after removing instances with scope $scope, instances are "+protocolledElements)
      }
    }

  }
}







