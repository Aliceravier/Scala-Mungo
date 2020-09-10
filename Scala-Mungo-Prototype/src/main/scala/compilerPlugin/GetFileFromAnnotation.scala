package compilerPlugin

import java.io.{File, FileInputStream, ObjectInputStream}
import java.nio.file.{Files, Paths}

import ProtocolDSL.{ReturnValue, State}

import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.reflect.api.Trees
import scala.sys.process._
import scala.tools.nsc.plugins.{Plugin, PluginComponent}
import scala.tools.nsc.{Global, Phase}
import scala.util.control.Breaks._

/** Holds an instance and its current possible states */
case class Instance(var className: String, var name:String, var currentStates:Set[State], var scope: mutable.Stack[String]){
  def updateState(stateToRemove:State, stateToAdd:State): Unit ={
    this.currentStates -= stateToRemove
    this.currentStates += stateToAdd
  }
  override def toString(): String={
    this.className + " " + this.name +" "+ this.currentStates+" "+scope.reverse.mkString(".")
  }
  override def equals(instance:Any): Boolean ={
    instance match{
      case i:Instance => i.name.equals(name) && i.canEqual(this) && i.scope.equals(scope) && i.className.equals(className)
      case _ => false
    }
  }

  override def hashCode():Int={
    name.hashCode + className.hashCode + scope.hashCode
  }
}

/** Holds information about a class or an object */
case class ElementInfo(className:String, transitions:Array[Array[State]], states:Array[State],
                       methodToIndices:mutable.HashMap[String, Set[Int]], isObject:Boolean=false){
  override def toString(): String={
    this.className + " " + transitions.foreach(_.mkString(", ")) + " " + states.mkString(", ") + " " + methodToIndices + " " + isObject
  }
}

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
  case class Function(name: String, params: ArrayBuffer[Array[String]], body: Tree, scope: String) {
    override def toString(): String = {
      s"name: $name parameters: $params scope: $scope"
    }
  }
  /** Error for when the user defines their protocol wrong */
  class badlyDefinedProtocolException(message:String) extends Exception(message)
  /** Error for when a protocol is violated */
  class protocolViolatedException(message:String) extends Exception(message)
  val runsAfter: List[String] = List[String]("refchecks")
  val phaseName: String = "compilerPlugin.GetFileFromAnnotation.this.name"
  def newPhase(_prev: Phase) = new GetFileFromAnnotationPhase(_prev)
    /** Phase which is ran by the plugin */
    class GetFileFromAnnotationPhase(prev: Phase) extends StdPhase(prev) {
      var compilationUnit:CompilationUnit=_
      var currentScope:mutable.Stack[String] = mutable.Stack()
      val Undefined = "_Undefined_"
      val Unknown = "_Unknown_"
      var curentElementInfo: ElementInfo=_
      override def name: String = "compilerPlugin.GetFileFromAnnotation.this.name"

      /** Entry point of the plugin. Goes through the code collecting object, class and function bodies, then checks
       * the code for protocol violations
       * @param unit: contains tree of the code in body
       */
      def apply(unit: CompilationUnit): Unit = {
        compilationUnit = unit
        //find all the classes, objects and functions in the code so we can jump to them later
        classAndObjectTraverser.traverse(unit.body)
        functionTraverser.traverse(unit.body)
        checkCode()
      }

      /** Finds objects and classes with protocols and goes through the code checking that the protocols are followed
       *
       */
      def checkCode(): Unit ={
        var setOfClassesWithProtocols: Set[String] = Set()
        for (tree@q"$mods class $className[..$tparams] $ctorMods(...$paramss) extends { ..$earlydefns } with ..$parents { $self => ..$body }" <- compilationUnit.body) {
          checkElement(body, getScope(tree)+ s".${className.toString()}", tree) match{
            case Some(className) => setOfClassesWithProtocols += className
            case None =>
          }
        }
        for(tree@q"$mods object $objectName extends { ..$earlydefns } with ..$parents { $self => ..$body }" <- compilationUnit.body){
          checkElement(body, objectName.toString, tree, true) match{
            case Some(objectName) => setOfClassesWithProtocols += objectName
            case None =>
          }
        }
      }

      /** Checks whether the object or class is following its protocol in the code.
       * It first checks if the element has a typestate annotation, then runs the protocol and collects the information
       * from it.
       * Then it checks the methods in the protocol are a subset of those defined in the element.
       * Then it checks the protocol is followed
       * @param body
       * @param name
       * @param tree
       * @param isObject
       * @return
       */
      def checkElement(body:Seq[Trees#Tree], name:String, tree:Tree, isObject:Boolean=false): Option[String] ={
        val annotations = tree.symbol.annotations
        for(annotation@AnnotationInfo(arg1,arg2, arg3) <- annotations){
          getFilenameFromTypestateAnnotation(annotation) match{
            case Some(filename) => { //a correct Typestate annotation is being used
              //execute the DSL in the protocol file and serialize the data into a file
              executeFile(filename)
              //retrieve the serialized data
              if(!Files.exists(Paths.get("protocolDir\\EncodedData.ser")))
                throw new badlyDefinedProtocolException(s"The protocol at $filename could not be processed, " +
                  s"check you have an end statement at the end of the protocol")
              val (transitions, states, returnValuesArray) = getDataFromFile("protocolDir\\EncodedData.ser")
              rmProtocolDir()
              checkProtocolMethodsSubsetClassMethods(returnValuesArray, body, name, filename)
              val methodToIndices = createMethodToIndicesMap(returnValuesArray)
              curentElementInfo = ElementInfo(name, transitions, states, methodToIndices, isObject)
              println("checking class "+curentElementInfo.className)
              checkElementIsUsedCorrectly()
              Some(name)
            }
            case None => None
          }
        }
        None
      }

      /** Creates a hashmap from method names (e.g. "walk(String)")
       * to indices at which it is present in the transitions array (including all the return values it might have)
       *
       * @param returnValuesArray:Array[ReturnValue]
       * @return mutable.Hashmap[String, Set[Int]]
       */
      def createMethodToIndicesMap(returnValuesArray:Array[ReturnValue]): mutable.HashMap[String, Set[Int]] ={
        var methodToIndices:mutable.HashMap[String, Set[Int]] = mutable.HashMap()
        for(returnValue <- returnValuesArray){
          methodToIndices += (stripReturnValue(returnValue.parentMethod.name) -> returnValue.parentMethod.indices)
        }
        methodToIndices
      }

      /** Checks that a class or object is following its protocol
       * Goes through the code to find either the object with App or the main function and thereby gets the entrypoint
       * of the code and can start analysing it from there.
       *
       * Limited at the moment
       *
       * TODO Deal with code inside object which contains main function
       *  Make work on more than a single file
       *  Take into account linearity
       *  Deal with objects returned by a function
       *  Deal with try-catch
       *  Deal with if on its own
       *  Deal with code on itself in constructor
       *  Deal with companion objects
       *  Deal with code inside parameters and conditions
       * */
      def checkElementIsUsedCorrectly(): Unit ={
        for(line@q"$mods object $tname extends { ..$earlydefns } with ..$parents { $self => ..$body }" <- compilationUnit.body){
          breakable {
            for (parent <- parents) {
              if (parent.toString() == "App") {
                currentScope.push(line.symbol.owner.fullName)
                currentScope.push(tname.toString())
                checkInsideAppBody(body)
                break
              }
            }
            for (definition <- body) {
              definition match {
                case line@q"$mods def main[..$tparams](...$paramss): $tpt = $expr" =>
                  currentScope.push(line.symbol.owner.fullName)
                  currentScope.push("main")
                  if (getParameters(paramss) == "Array[String]") checkInsideFunctionBody(expr)
                case _ =>
              }
            }
          }
        }
      }

      /** Goes inside "App" object to see if there are instances with protocols and if they are following their protocol
       * Analyses the code line by line with processLine. Takes instances or creates a new set of them and returns
       * updated ones
       *
       * @param code
       */
      def checkInsideAppBody(code:Seq[Trees#Tree], givenInstances:Set[Instance]=Set()): Set[Instance] = {
        var instances = for (instance <- givenInstances) yield instance
        if(curentElementInfo.isObject) instances +=Instance(curentElementInfo.className, curentElementInfo.className, Set(curentElementInfo.states(0)), currentScope.clone())
        for (line <- code) {
            val newInstanceAndNbLinesToSkip = processLine(line, instances)
            instances = newInstanceAndNbLinesToSkip._1
        }
        println("\nInstances:")
        instances.foreach(println)
        instances
      }


      /** Checks the code inside the body of a function for protocol violations.
       * Goes line by line and skips lines if needed (for example when at a definition or a loop).
       *
       * @param code
       * @param givenInstances
       * @return
       */
      def checkInsideFunctionBody(code:Trees#Tree, givenInstances:Set[Instance]=Set()): Set[Instance] ={
        var instances = for (instance <- givenInstances) yield instance
        if(curentElementInfo.isObject) instances += Instance(curentElementInfo.className, curentElementInfo.className, Set(curentElementInfo.states(0)), currentScope.clone())
        var nbOfLinesToSkip = 0
        for (line <- code) {
          breakable {
            if(nbOfLinesToSkip>0){
              nbOfLinesToSkip-=1
              break
            }
            val newInstanceAndNbLinesToSkip = processLine(line, instances)
            instances = newInstanceAndNbLinesToSkip._1
            nbOfLinesToSkip = newInstanceAndNbLinesToSkip._2
          }
        }
        println("\nInstances:")
        instances.foreach(println)
        instances
      }


      /** Checks a line and returns possibly updated instances.
       * Has different cases for different types of line
       *
       * @param line line of code to analyse
       * @param instances instances to potentially update
       * @return
       */
      def processLine(line:Trees#Tree, instances: Set[Instance]): (Set[Instance], Int) ={
        println("checking line "+line+" at line number "+line.pos.line)
        line match {
          //definitions to skip over (object, class, function)
          case q"$modifs object $tname extends { ..$earlydefins } with ..$pparents { $sself => ..$body }" =>
            (instances, getLengthOfTree(line)-1)
          case q"$mod class $pname[..$tpara] $actMods(...$para) extends { ..$defs } with ..$prnts { $self => ..$sts }" =>
            (instances, getLengthOfTree(line)-1)
          case q"$mods def $name[..$tparams](...$paramss): $tpt = $expr"=>
            (instances, getLengthOfTree(line)-1)
          case q"$mods val $tname: $tpt = new $classNm(...$exprss)" =>
            var newInstances = processNewInstance(tname, classNm, instances)
            (newInstances,0)
          case q"$mods var $tname: $tpt = new $classNm(...$exprss)" =>
            var newInstances = processNewInstance(tname, classNm, instances)
            (newInstances,0)
          case q"for (..$enums) $expr" => {
            val newInstances = dealWithLoopContents(instances, expr)
            (newInstances, getLengthOfTree(line)-1) //-1 because we are processing the current one already
          }
          //while(true) and dowhile(true)
          case LabelDef(TermName(name), List(), block @ Block(statements, Apply(Ident(TermName(name2)), List())))
            if (name.startsWith("while$") || name.startsWith("doWhile$")) && name2 == name =>{
            val newInstances = dealWithLoopContents(instances, block.asInstanceOf[Trees#Tree])
            (newInstances, getLengthOfTree(line)-1) //-1 because we are processing the current one already
          }
          case q"while ($cond) $expr" =>{
            val newInstances = dealWithLoopContents(instances, expr)
            (newInstances, getLengthOfTree(line)-1) //-1 because we are processing the current one already
          }
          case q"do $expr while ($cond)" =>{
            val newInstances = dealWithLoopContents(instances, expr)
            checkInsideFunctionBody(expr, instances)
            (newInstances, 0)
          }
          case q"for (..$enums) yield $expr" =>{
            val newInstances = dealWithLoopContents(instances, expr)
            (newInstances, getLengthOfTree(line)-1) //-1 because we are processing the current one already
          }
          //Functions (first is for functions defined in the same scope, second the others)
          case func@Apply(Ident(functionName), args) =>{
            val newInstances = dealWithFunction(func, functionName, args, instances)
            val updatedInstances = updateStateIfNeeded(newInstances, line)
            (updatedInstances, 0) //because we are processing the current one already
          }
          case func@Apply(Select(instanceCalledOn, functionName), args) =>{
            val newInstances = dealWithFunction(func, functionName, args, instances, instanceCalledOn)
            val updatedInstances = updateStateIfNeeded(newInstances, line)
            (updatedInstances, 0) //because we are processing the current one already
          }
          case q"if ($cond) $ifBody else $elseBody" =>
            val newInstances = dealWithIfElse(ifBody, elseBody, instances)
            (newInstances,getLengthOfTree(line)-1)
          //All three next cases are to check for solitary object name on a line
          case Ident(TermName(objectName)) =>
            checkObject(objectName, instances)
            (instances,0)
          case Select(location, expr) =>
            var exprString = expr.toString()
            if(exprString.lastIndexOf(".") != -1)
              exprString = exprString.substring(exprString.lastIndexOf(".")+1)
            checkObject(exprString, instances)
            (instances,0)
          case Block(List(expr), Literal(Constant(()))) =>
            var exprString = expr.toString()
            if(exprString.lastIndexOf(".") != -1)
              exprString = exprString.substring(exprString.lastIndexOf(".")+1)
            checkObject(exprString, instances)
            (instances,0)
          //default case
          case _ => (instances,0)
        }
      }

      /** Handles an if-else statement. Saves the current state of instances in ifInstances and elseInstances then
       * goes through both paths and gets new states for the instances. Once this is done it merges the two possible
       * paths and instances now hold all possible states they could be in after going through either path.
       *
       * @param ifBody
       * @param elseBody
       * @param instances
       * @return
       */
      def dealWithIfElse(ifBody: Trees#Tree, elseBody: Trees#Tree, instances:Set[Instance]):Set[Instance] = {
        var ifInstances = for (instance <- instances) yield instance
        ifInstances = checkInsideFunctionBody(ifBody, ifInstances)
        var elseInstances = for (instance <- instances) yield instance
        elseInstances = checkInsideFunctionBody(elseBody, elseInstances)
        mergeInstanceStates(ifInstances, elseInstances)
      }

      /** Handles code which creates a new instance of a class. Checks if the new instance is of the class we are
       * currently handling and then checks if the instance is already defined in the list of instances.
       * If so, it replaces the old instance with a new one. If not it adds a new instance to the list.
       *
       * @param instanceTree
       * @param classTree
       * @param instances
       * @return
       */
      def processNewInstance(instanceTree:TermName, classTree:Tree, instances:Set[Instance]):Set[Instance]= {
        val className = curentElementInfo.className
        val states = curentElementInfo.states
        var newInstances = for (instance <- instances) yield instance
        if (getScope(classTree)+s".${classTree.toString}" == className) {
          for (newInstance <- newInstances if newInstance == Instance(className, instanceTree.toString(), Set(), currentScope))
            newInstances -= newInstance
          newInstances += Instance(className, instanceTree.toString(), Set(states(0)), currentScope.clone())
        }
        newInstances
      }


      /** For two sets of instances, if and instance is present in both of them, merges the different states
       * associated with it into one instance. Copies over the remaining instances which are only present once.
       *
       * @param firstInstances
       * @param secondInstances
       * @return
       */
      def mergeInstanceStates(firstInstances:Set[Instance], secondInstances:Set[Instance]): Set[Instance] ={
        var mergedInstances:Set[Instance] = Set()
        for(firstInstance <- firstInstances){
          secondInstances.find(instance => instance == firstInstance) match{
            case Some(instance) =>
              mergedInstances += Instance(firstInstance.className, firstInstance.name,
                firstInstance.currentStates ++ instance.currentStates, firstInstance.scope)
            case None => mergedInstances += firstInstance
          }
        }
        for(secondInstance <- secondInstances) if(!firstInstances.contains(secondInstance)){
          mergedInstances += secondInstance
        }
        mergedInstances
      }

      /** Gets the instance with hte closest scope to the current scope with the given name if it exists. If not,
       * returns None.
       *
       * @TODO At the moment this does not actually get the closest scope but just the instance with the longest scope.
       *
       * @param name
       * @param instances
       * @return
       */
      def getClosestScopeInstance(name:String, instances:Set[Instance]): Option[Instance] ={
        if(instances.isEmpty) return None
        var closestScope:mutable.Stack[String] = mutable.Stack()
        var closestInstance = Instance("dummyClass","dummyInstance", Set(), mutable.Stack())
        var foundInstance = false
        for(instance <- instances) {
          if(instance.name == name){
            if(closestScope.size < instance.scope.size) {
              closestScope = instance.scope
              closestInstance = instance
              foundInstance = true
            }
          }
        }
        if(foundInstance) return Some(closestInstance)
        None
      }

      /** Checks if the object (calledOn) is defined in the file and if it has not already been initialised, runs the
       * code inside the object.
       *
       * @param calledOn
       * @param instances
       */
      def checkObjectFunctionCall(calledOn: global.Tree, instances:Set[Instance]): Unit = {
        if(calledOn == null) return
        var calledOnString = calledOn.toString()
        if(calledOnString.lastIndexOf(".") != -1)
          calledOnString = calledOn.toString.substring(calledOn.toString.lastIndexOf(".")+1)
        for (element <- classAndObjectTraverser.classesAndObjects
             if(!element.initialised && element.isObject && element.name == calledOnString
               && element.scope == getScope(calledOn))) {
          element.initialised = true
          currentScope.push(calledOnString)
          checkInsideAppBody(element.body, instances)
          currentScope.pop()
        }
      }

      /** Checks function calls.
       * Firstly it checks if the function is new x and therefore the code inside a class should be analysed.
       * Secondly it checks if an object is being called on for the first time and its code should be analysed.
       * Then it goes to analyse inside the function body, renaming the instances to parameter names if needed.
       *
       * @param funcCall
       * @param functionName
       * @param args
       * @param instances
       * @param calledOn
       * @return
       */
      def dealWithFunction(funcCall: global.Apply, functionName: global.Name, args: List[global.Tree], instances:Set[Instance], calledOn:Tree=null):Set[Instance] = {
        println("found function call "+funcCall)
        checkNewFunction(funcCall, instances, args)
        checkObjectFunctionCall(calledOn, instances)
        //finding function definition
        val functionScope = getScope(funcCall, true)
        for (function <- functionTraverser.functions){
          if(function.name == functionName.toString() && function.scope == functionScope) {
            println("matched functions, found body "+function.body)
            //handling parameters on entry
            val paramNameToInstanceName = handleParameters(args, function, instances)
            //checking inside the function body
            currentScope.push(functionName.toString())
            val newInstances = checkInsideFunctionBody(function.body, instances)
            currentScope.pop()
            //renaming parameters on exit
            for(mapping <- paramNameToInstanceName) {
              for(instance <- instances if instance.name == mapping._1) instance.name = mapping._2
            }
            return newInstances
          }
        }
        instances
      }

      /** Gets the named object of closest scope to the current scope.
       *
       * @TODO At the moment just gets the longest scope
       *
       * @param objectName
       * @return
       */
      def getClosestScopeObject(objectName: String): Option[ClassOrObject] ={
        var classesAndObjects = classAndObjectTraverser.classesAndObjects
        if(classesAndObjects.isEmpty) return None
        var closestScope = ""
        var closestObject = ClassOrObject("myObj",ArrayBuffer(), null, "")
        var foundObject = false
        for(element <- classesAndObjects) {
          if(element.name == objectName && element.isObject){
            if(closestScope.length < element.scope.length) {
              closestScope = element.scope
              closestObject = element
              foundObject = true
            }
          }
        }
        if(foundObject) return Some(closestObject)
        None
      }

      /** Checks if the object given has been seen before. If not, executes the code inside it.
       *
       * @param objectName
       * @param instances
       * @return
       */
      def checkObject(objectName: String, instances:Set[Instance]) = {
        getClosestScopeObject(objectName) match{
          case Some(obj) =>
            obj.initialised = true
            currentScope.push(objectName)
            checkInsideAppBody(obj.body, instances)
            currentScope.pop()
          case _ =>
        }
      }

      /** Checks for a new x function and executes the code within the class if found. Renames instances to
       * constructor parameter names if needed.
       *
       * @param funcCall
       * @param instances
       * @param args
       */
      def checkNewFunction(funcCall: global.Apply, instances:Set[Instance], args: List[global.Tree]): Unit ={
        funcCall match{
          case q"new { ..$earlydefns } with ..$parents { $self => ..$stats }" =>
            parents match {
              case List(Apply(elementName, arg2)) =>
                var elementNameString = elementName.toString()
                if(elementNameString.lastIndexOf(".") != -1)
                  elementNameString = elementNameString.substring(elementNameString.lastIndexOf(".")+1)
                for (element <- classAndObjectTraverser.classesAndObjects
                     if(!element.isObject && element.name == elementNameString
                       && element.scope == getScope(elementName))) {
                  val paramNameToInstanceName = handleParameters(args, element, instances)
                  currentScope.push(element.name)
                  checkInsideAppBody(element.body, instances)
                  currentScope.pop()
                  for (mapping <- paramNameToInstanceName)
                    for (instance <- instances if instance.name == mapping._1) instance.name = mapping._2
                }
              case _ =>
            }
          case _ =>
        }
      }

      /** For the parameter list given, checks if they match any of our defined instances. If so, renames the instance
       * to the parameter name. Keeps memory of the renaming in a hashmap of parameter name to instance name so these
       * can easily be renamed after the function exits.
       *
       * @param args
       * @param function
       * @param instances
       * @return
       */
      def handleParameters(args:List[global.Tree], function:Function, instances:Set[Instance]): mutable.HashMap[String, String] ={
        var paramNameToInstanceName = new mutable.HashMap[String, String]
        var argCounter = 0
        for(arg <- args){
          var argString = arg.toString()
          if(argString.contains(".")) argString = argString.substring(argString.lastIndexOf(".")+1)
          getClosestScopeInstance(argString,instances) match{
            case Some(instance) =>
              val paramName = function.params(argCounter)(0)
              paramNameToInstanceName += paramName -> instance.name
              instance.name = paramName
            case None =>
          }
          argCounter += 1
        }
        paramNameToInstanceName
      }

      /** For the parameter list given, checks if they match any of our defined instances. If so, renames the instance
       * to the parameter name. Keeps memory of the renaming in a hashmap of parameter name to instance name so these
       * can easily be renamed after the class exits.
       *
       * @param args
       * @param element
       * @param instances
       * @return
       */
      def handleParameters(args:List[global.Tree], element:ClassOrObject, instances:Set[Instance]): mutable.HashMap[String, String] ={
        var paramNameToInstanceName = new mutable.HashMap[String, String]
        var argCounter = 0
        for(arg <- args){
          var argString = arg.toString()
          if(argString.contains(".")) argString = argString.substring(argString.lastIndexOf(".")+1)
          getClosestScopeInstance(argString,instances) match{
            case Some(instance) =>
              val paramName = element.params(argCounter)(0)
              paramNameToInstanceName += paramName -> instance.name
              instance.name = paramName
            case None =>
          }
          argCounter += 1
        }
        paramNameToInstanceName
      }

      /** Handles any for or while loop.
       * It goes through the contents of the for loop and checks what the states of all the instances are at the end.
       * It stores theses states in a list (one for each instance) and checks to see if all instances have looped
       * (i.e. they have twice the same set of states in their list). If so it gets out of the for loop. It then
       * gives all instances all the states they went through while looping since we don't know how many times the loop
       * will iterate between 0 and infinity times. This asumes that users cannot write infinte loops into their
       * protocols, otherwise this would never terminate.
       *
       * @TODO Bug: while loop might end early if only one instance has completed its looping before others have
       * @Fix: Just check if all lists have duplicates rather than only one.
       *
       * @param instances
       * @param loopContent
       * @return
       */
      def dealWithLoopContents(instances:Set[Instance], loopContent:Trees#Tree): Set[Instance] ={
        var newInstances = for(instance <- instances) yield instance
        var instanceToInterimStates: mutable.HashMap[Instance, ListBuffer[Set[State]]] = mutable.HashMap()
        for(instance <- newInstances) instanceToInterimStates += instance -> ListBuffer()
        do{
          for(instance <- newInstances if instanceToInterimStates.contains(instance))
            instanceToInterimStates(instance) += instance.currentStates
          for(line <- loopContent) {
            newInstances = processLine(line, newInstances)._1
            for(updatedInstance <- newInstances if instanceToInterimStates.contains(updatedInstance)) {
              instanceToInterimStates(updatedInstance)(instanceToInterimStates(updatedInstance).length - 1) = updatedInstance.currentStates
            }
          }
        } while(!duplicatesInListsOfMap(instanceToInterimStates))
        for(instance <- newInstances if instanceToInterimStates.contains(instance)){
          for(setOfStates <- instanceToInterimStates(instance))
            instance.currentStates = instance.currentStates ++ setOfStates
        }
        newInstances
      }

      /** Checks for duplicates in the lists of a map(Instance -> list) */
      def duplicatesInListsOfMap(map:mutable.HashMap[Instance, ListBuffer[Set[State]]]):Boolean={
        for((instance, list) <- map) for((instance, list) <- map if list.diff(list.distinct).nonEmpty) return true
        false
      }

      /** For a given line of code, checks if it is a method on an instance with protocol and if so updates its state
       *
       * @param instances
       * @param line
       */
      def updateStateIfNeeded(instances: Set[compilerPlugin.Instance], line:Trees#Tree): Set[Instance] ={
        println("inside update state for line "+line)
        val methodToStateIndices = curentElementInfo.methodToIndices
        val className = curentElementInfo.className
        line match{
          case app@Apply(fun, args) => {
            methodTraverser.traverse(app)
          }
          case _ =>
        }
        val methodCallInfos = methodTraverser.methodCallInfos
        for(methodCallInfo <- methodCallInfos){
          val methodName = methodCallInfo(0)
          val instanceName = methodCallInfo(1)
          println("instances inside update are "+instances)
          println("instance name to find within those is "+instanceName)
          getClosestScopeInstance(instanceName, instances) match{
            case Some(instance) =>
              println("got instance"+instance)
              println("it has "+ instance.currentStates)
              println("method to indices is "+curentElementInfo.methodToIndices)
              breakable {
                var newSetOfStates:Set[State] = Set()
                for(state <- instance.currentStates) {
                  if (state.name == Unknown) break
                  if (methodToStateIndices.contains(methodName)) {
                    println("found method name "+methodName)
                    val indexSet = methodToStateIndices(methodName)
                    println("index set is "+indexSet)
                    var newStates:Set[State] = Set[State]()
                    newStates += curentElementInfo.transitions(state.index)(indexSet.min)
                    if(indexSet.size > 1 && curentElementInfo.transitions(state.index)(indexSet.min).name == Undefined)
                        newStates = for(x <- indexSet - indexSet.min) yield curentElementInfo.transitions(state.index)(x)
                    println("new states are "+newStates)
                    for(state <- newStates if state.name == Undefined) {
                      throw new protocolViolatedException(s"Invalid transition in instance $instanceName of type $className " +
                        s"from state(s) ${instance.currentStates} with method $methodName " +
                        s"in file ${line.pos.source} at line ${line.pos.line}")
                    }
                    newSetOfStates = newSetOfStates ++ newStates
                  }
                  else instance.currentStates = Set(State(Unknown, -2))
                }
                instance.currentStates = newSetOfStates
              }
            case None =>
          }
        }
        //reset the traverser's list to be empty
        methodTraverser.methodCallInfos = ListBuffer[Array[String]]()
        println("instances at the end of update if needed are "+instances)
        instances
      }



      /** Traverses a tree and collects (methodName, instanceName) from method application statements
       *
       */
      object methodTraverser extends Traverser {
        var methodCallInfos = ListBuffer[Array[String]]()
        override def traverse(tree: Tree): Unit = {
          tree match {
            case app@Apply(fun, args) =>
              app match {
                case q"$expr(...$exprss)" =>
                  expr match {
                    case select@Select(qualifier, name) => {
                      var instanceName = qualifier.toString()
                      if(qualifier.hasSymbolField) instanceName = qualifier.symbol.name.toString
                      println("method name is "+name.toString().appendedAll(getParametersFromTree(exprss)))
                      println("instance name is "+instanceName)
                      methodCallInfos +=
                        Array(name.toString().appendedAll(getParametersFromTree(exprss)),
                          instanceName)
                    }
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

      case class ClassOrObject(name:String, params:ArrayBuffer[Array[String]], body:Seq[Trees#Tree], scope:String,
                               isObject:Boolean=false, var initialised:Boolean=false){
        override def toString(): String={ s"$name ${showParams(params)} $scope $initialised" }

        def showParams(params:ArrayBuffer[Array[String]]):String={
          var parameters = ""
          for(param <- params) {
            for(par <- param)
              parameters += par+": "
            parameters += " ; "
          }
          parameters
        }
      }

      /** Traverses a tree and collects classes and objects found */
      object classAndObjectTraverser extends Traverser{
        var classesAndObjects = ListBuffer[ClassOrObject]()
        override def traverse(tree: Tree): Unit = {
          tree match{
            case obj@q"$mods object $tname extends { ..$earlydefns } with ..$parents { $self => ..$body }" =>
              classesAndObjects += ClassOrObject(tname.toString, ArrayBuffer(), body, getScope(obj), isObject = true)
              super.traverse(obj)
            case cla@q"$mods class $tpname[..$tparams] $ctorMods(...$paramss) extends { ..$earlydefns } with ..$parents { $self => ..$stats }" =>
              val parameters = getParametersWithInstanceNames(paramss)
              classesAndObjects += ClassOrObject(tpname.toString(), parameters, stats, getScope(cla))
              super.traverse(cla)
            case _ =>
              super.traverse(tree)
          }
        }
      }

      /** Gathers function definitions in a tree inside "functions" */
      object functionTraverser extends Traverser{
        var functions = ListBuffer[Function]()
        override def traverse(tree: Tree): Unit = {
          tree match {
            case  func@q"$mods def $tname[..$tparams](...$paramss): $tpt = $expr" =>
              val parameters = getParametersWithInstanceNames(paramss)
              if(tname.toString() != "<init>")
                functions += Function(tname.toString(), parameters, expr, getScope(func))
              super.traverse(expr)
            case _ =>
              super.traverse(tree)
          }
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
      def getScope(obj:Tree, dontCheckSymbolField:Boolean = false): String ={
        var objectScope = ""
        if(obj.hasSymbolField || dontCheckSymbolField) {
          for (symbol <- obj.symbol.owner.ownerChain.reverse)
            objectScope += symbol.name + "."
          objectScope = objectScope.substring(0, objectScope.lastIndexOf('.'))
        }
        objectScope
      }

      /** Gets the length of a tree in nb of lines */
      def getLengthOfTree(tree:Trees#Tree): Int ={
        var length = 0
        for(line <- tree) length +=1
        length
      }

      /** From a scope implemented as a stack, gets a string formatted with dots */
      def getScopeString(scopeStack:mutable.Stack[String]): String ={
        scopeStack.reverse.mkString(".")
      }

      /** Gets a parameter string as formatted in a function definition from a tree of them */
      def getParametersFromTree(params:List[List[Tree]]): String={
        params match{
          case List(List()) => "()"
          case List(List(value)) => keepOnlyMethodName(value.tpe.toString()).mkString("(","",")")
          case List(values) => {
            var parameters:ArrayBuffer[String] = ArrayBuffer()
            for(elem <- values){
              parameters += keepOnlyMethodName(elem.tpe.toString)
            }
            parameters.mkString("(",",",")")
          }
          case _ => ""
        }
      }

      /** Takes a string a strips everything after ( from it */
      def keepOnlyMethodName(method:String): String ={
        if(method.contains('(')) method.substring(0,method.indexOf('('))
        else method
      }

      /** Prints something easy to see while debugging, use above an interesting print statement */
      def printBanner(): Unit ={
        println("------------------")
        println("LOOK HERE")
        println("------------------")
      }

      /** Just a dummy function to check an object's type */
      def ckType(s:String): Unit ={
        println("hi")
      }

      /** Checks that methods in return values are a subset of those in stats
       *
       * @param returnValuesArray
       * @param stats
       * @param className
       * @param filename
       */
      def checkProtocolMethodsSubsetClassMethods(returnValuesArray:Array[ReturnValue], stats: Seq[Trees#Tree], className:String, filename:String): Unit ={
        val classMethodSignatures = getMethodNames(stats)
        println(s"\n$classMethodSignatures")
        var protocolMethodSignatures: Set[String] = Set()
        for(i <- returnValuesArray.indices){
          protocolMethodSignatures += stripReturnValue(returnValuesArray(i).parentMethod.name.replaceAll("\\s", ""))
        }
        println(protocolMethodSignatures)
        if(!(protocolMethodSignatures subsetOf classMethodSignatures)) throw new badlyDefinedProtocolException(
          s"Methods $protocolMethodSignatures defined in $filename are not a subset of methods " +
            s"$classMethodSignatures defined in class $className")
      }



      /** Gets rid of the return value in a method name string and keeps the parenthesis at the end */
      def stripReturnValue(methodName:String): String ={
        if(!(methodName.contains(':') || methodName.contains("()"))) methodName+"()"
        else if(methodName.contains(':') && !methodName.contains("(") && !methodName.contains(")")) methodName.substring(0,methodName.indexOf(':'))+"()"
        else if(methodName(methodName.length-1) == ')') methodName
        else methodName.substring(0,methodName.indexOf(')')+1)
      }

      /** Checks if an annotation is a Typestate annotation and returns the filename if so
       *
       * @param annotation
       * @return
       */
      def getFilenameFromTypestateAnnotation(annotation: AnnotationInfo):Option[String] ={
        annotation match{
          case AnnotationInfo(arg1, arg2, arg3) =>
            if(arg1.toString == "Typestate" || arg1.toString == "compilerPlugin.Typestate") {
              Some(arg2.head.toString())
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
      def getMethodNames(methodBody: Seq[Trees#Tree]): Set[String]={
        var methodNames: Set[String] = Set()
        for(line <- methodBody){
          line match{
            case q"$mods def $tname[..$tparams](...$paramss): $tpt = $expr" => {
              val parameters = getParameters(paramss) //RED UNDERLINING IS WRONG HERE
              methodNames += tname+s"($parameters)"
            }
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
      def getParameters(params:List[List[ValDef]]): String ={
        params match{
          case List(List()) => ""
          case List(List(value)) => {
            println(value.name.toString())
            value.tpt.toString()
          }
          case List(values) => {
            var parameters:ArrayBuffer[String] = ArrayBuffer()
            for(elem <- values){
              println(elem.name.toString())
              parameters += elem.tpt.toString
            }
            parameters.mkString(",")
          }
          case _ => ""
        }
      }

      /** Gets parameters from a tree as their name and type in a string array */
      def getParametersWithInstanceNames(params:List[List[ValDef]]): ArrayBuffer[Array[String]] ={
        params match{
          case List(List()) => ArrayBuffer(Array(""))
          case List(List(value)) => ArrayBuffer(Array(value.name.toString(), value.tpt.toString()))
          case List(values) => {
            var parameters:ArrayBuffer[Array[String]] = ArrayBuffer()
            for(elem <- values){
              parameters += Array(elem.name.toString(), elem.tpt.toString)
            }
            parameters
          }
          case _ => ArrayBuffer()
        }
      }

      /** Creates an sbt project, copies in the dsl and the user protocol and executes it, giving serialized data in a the project folder*/
      def executeFile(filename:String): Unit ={
        println(filename)
        s"executeUserProtocol.bat $filename".!
      }

      /** Removes protocolDir from the project */
      def rmProtocolDir(): Unit ={
        s"cleanUp.bat".!
      }

      /** Returns protocol data from a file */
      def getDataFromFile(filename: String): (Array[Array[State]], Array[State], Array[ReturnValue]) ={
        val ois = new ObjectInputStream(new FileInputStream(filename))
        val stock = ois.readObject.asInstanceOf[(Array[Array[State]], Array[State], Array[ReturnValue])]
        ois.close
        val file = new File(filename)
        file.delete
        stock
      }
    }
}