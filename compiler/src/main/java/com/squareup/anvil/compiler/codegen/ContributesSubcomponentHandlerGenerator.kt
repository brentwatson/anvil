package com.squareup.anvil.compiler.codegen

import com.squareup.anvil.annotations.MergeSubcomponent
import com.squareup.anvil.compiler.ANVIL_SUBCOMPONENT_SUFFIX
import com.squareup.anvil.compiler.COMPONENT_PACKAGE_PREFIX
import com.squareup.anvil.compiler.ClassScanner
import com.squareup.anvil.compiler.HINT_SUBCOMPONENTS_PACKAGE_PREFIX
import com.squareup.anvil.compiler.PARENT_COMPONENT
import com.squareup.anvil.compiler.SUBCOMPONENT_FACTORY
import com.squareup.anvil.compiler.SUBCOMPONENT_MODULE
import com.squareup.anvil.compiler.api.AnvilContext
import com.squareup.anvil.compiler.api.CodeGenerator
import com.squareup.anvil.compiler.api.GeneratedFile
import com.squareup.anvil.compiler.api.createGeneratedFile
import com.squareup.anvil.compiler.contributesSubcomponentFactoryFqName
import com.squareup.anvil.compiler.contributesSubcomponentFqName
import com.squareup.anvil.compiler.contributesToFqName
import com.squareup.anvil.compiler.internal.annotation
import com.squareup.anvil.compiler.internal.argumentType
import com.squareup.anvil.compiler.internal.asClassName
import com.squareup.anvil.compiler.internal.buildFile
import com.squareup.anvil.compiler.internal.findAnnotation
import com.squareup.anvil.compiler.internal.findAnnotationArgument
import com.squareup.anvil.compiler.internal.generateClassName
import com.squareup.anvil.compiler.internal.getAnnotationValue
import com.squareup.anvil.compiler.internal.hasAnnotation
import com.squareup.anvil.compiler.internal.parentScope
import com.squareup.anvil.compiler.internal.reference.AnvilCompilationExceptionClassReference
import com.squareup.anvil.compiler.internal.reference.ClassReference
import com.squareup.anvil.compiler.internal.reference.FunctionReference
import com.squareup.anvil.compiler.internal.reference.Visibility.PUBLIC
import com.squareup.anvil.compiler.internal.reference.asClassName
import com.squareup.anvil.compiler.internal.reference.classesAndInnerClasses
import com.squareup.anvil.compiler.internal.reference.daggerScopes
import com.squareup.anvil.compiler.internal.reference.innerClasses
import com.squareup.anvil.compiler.internal.reference.isAbstract
import com.squareup.anvil.compiler.internal.reference.isAnnotatedWith
import com.squareup.anvil.compiler.internal.reference.replaces
import com.squareup.anvil.compiler.internal.reference.returnType
import com.squareup.anvil.compiler.internal.reference.scope
import com.squareup.anvil.compiler.internal.reference.toClassReference
import com.squareup.anvil.compiler.internal.requireFqName
import com.squareup.anvil.compiler.internal.safePackageString
import com.squareup.anvil.compiler.internal.scope
import com.squareup.anvil.compiler.internal.toClassId
import com.squareup.anvil.compiler.internal.toFqNames
import com.squareup.anvil.compiler.mergeComponentFqName
import com.squareup.anvil.compiler.mergeInterfacesFqName
import com.squareup.anvil.compiler.mergeSubcomponentFqName
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.KModifier.OVERRIDE
import com.squareup.kotlinpoet.TypeSpec
import dagger.Binds
import dagger.Module
import dagger.Subcomponent
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtCollectionLiteralExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.constants.ArrayValue
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import java.io.File

/**
 * Looks for `@MergeComponent`, `@MergeSubcomponent` or `@MergeModules` annotations and generates
 * the actual contributed subcomponents that specified these scopes as parent scope, e.g.
 *
 * ```
 * @MergeComponent(Unit::class)
 * interface ComponentInterface
 *
 * @ContributesSubcomponent(Any::class, parentScope = Unit::class)
 * interface SubcomponentInterface
 * ```
 * For this code snippet the code generator would generate:
 * ```
 * @MergeSubcomponent(Any::class)
 * interface SubcomponentInterfaceAnvilSubcomponent
 * ```
 */
internal class ContributesSubcomponentHandlerGenerator(
  private val classScanner: ClassScanner
) : CodeGenerator {

  private val triggers = mutableListOf<Trigger>()
  private val contributions = mutableSetOf<Contribution>()
  private val replacedReferences = mutableSetOf<ClassReference>()
  private val processedEvents = mutableSetOf<GenerateCodeEvent>()

  private var isFirstRound = true

  override fun isApplicable(context: AnvilContext): Boolean = !context.generateFactoriesOnly

  override fun generateCode(
    codeGenDir: File,
    module: ModuleDescriptor,
    projectFiles: Collection<KtFile>
  ): Collection<GeneratedFile> {
    if (isFirstRound) {
      isFirstRound = false
      populateInitialContributions(module)
    }

    // Find new @MergeComponent (and similar triggers) that would cause generating new code.
    triggers += projectFiles
      .classesAndInnerClasses(module)
      .mapNotNull { clazz ->
        val annotation = generationTrigger.firstOrNull { trigger ->
          clazz.hasAnnotation(trigger, module)
        } ?: return@mapNotNull null

        val exclusions = clazz.excludeOrNull(module, annotation) ?: emptyList()

        Trigger(clazz, clazz.scope(annotation, module), exclusions)
      }

    // Find new contributed subcomponents in this module. If there's a trigger for them, then we
    // also need to generate code for them later.
    contributions += projectFiles
      .classesAndInnerClasses(module)
      .mapNotNull { clazz ->
        val annotation = clazz.findAnnotation(contributesSubcomponentFqName, module)
          ?: return@mapNotNull null

        val modules = annotation
          .findAnnotationArgument<KtCollectionLiteralExpression>("modules", 2)
          ?.toFqNames(module)
          ?: emptyList()

        val exclude = annotation
          .findAnnotationArgument<KtCollectionLiteralExpression>("exclude", 3)
          ?.toFqNames(module)
          ?: emptyList()

        Contribution(
          classReference = clazz.toClassReference(module),
          scope = clazz.scope(contributesSubcomponentFqName, module),
          parentScope = clazz.parentScope(contributesSubcomponentFqName, module),
          modules = modules,
          exclude = exclude
        )
      }
      .toList()
      .also { contributions ->
        // Find all replaced subcomponents and remember them.
        replacedReferences += contributions.flatMap { contribution ->
          contribution.classReference.replaces(contributesSubcomponentFqName)
            .also {
              checkReplacedSubcomponentWasNotAlreadyGenerated(contribution.classReference, it)
            }
        }
      }

    // Remove any contribution, if it was replaced by another contribution.
    contributions.removeAll { contribution ->
      contribution.classReference in replacedReferences
    }

    return contributions
      .flatMap { contribution ->
        triggers
          .filter { trigger ->
            trigger.scope == contribution.parentScope &&
              contribution.clazzFqName !in trigger.exclusions
          }
          .map { trigger ->
            GenerateCodeEvent(trigger, contribution)
          }
      }
      // Don't generate code for the same event twice.
      .minus(processedEvents)
      .also {
        processedEvents += it
      }
      .map { generateCodeEvent ->
        val contribution = generateCodeEvent.contribution
        val generatedAnvilSubcomponent = generateCodeEvent.generatedAnvilSubcomponent

        val generatedPackage = generatedAnvilSubcomponent.packageFqName.asString()
        val componentClassName = generatedAnvilSubcomponent.relativeClassName.asString()

        val factoryClass = findFactoryClass(contribution, generatedAnvilSubcomponent)

        val content = FileSpec.buildFile(generatedPackage, componentClassName) {
          TypeSpec
            .interfaceBuilder(componentClassName)
            .addSuperinterface(contribution.classReference.asClassName())
            .addAnnotation(
              AnnotationSpec
                .builder(MergeSubcomponent::class)
                .addMember("scope = %T::class", contribution.scope.asClassName(module))
                .apply {
                  fun addClassArrayMember(
                    name: String,
                    fqNames: List<FqName>
                  ) {
                    if (fqNames.isNotEmpty()) {
                      val classes = fqNames.map { it.asClassName(module) }
                      val template = classes
                        .joinToString(prefix = "[", postfix = "]") { "%T::class" }

                      addMember("$name = $template", *classes.toTypedArray())
                    }
                  }

                  addClassArrayMember("modules", contribution.modules)
                  addClassArrayMember("exclude", contribution.exclude)
                }
                .build()
            )
            .addAnnotations(contribution.classReference.daggerScopes())
            .apply {
              if (factoryClass != null) {
                addType(generateFactory(factoryClass.originalReference))
                addType(generateDaggerModule(factoryClass.originalReference))
              }
            }
            .addType(
              generateParentComponent(
                contribution, generatedAnvilSubcomponent, factoryClass
              )
            )
            .build()
            .also { addType(it) }
        }

        createGeneratedFile(
          codeGenDir = codeGenDir,
          packageName = generatedPackage,
          fileName = componentClassName,
          content = content
        )
      }
  }

  private fun generateFactory(
    factoryReference: ClassReference
  ): TypeSpec {
    val superclass = factoryReference.asClassName()

    val builder = if (factoryReference.isInterface()) {
      TypeSpec
        .interfaceBuilder(SUBCOMPONENT_FACTORY)
        .addSuperinterface(superclass)
    } else {
      TypeSpec
        .classBuilder(SUBCOMPONENT_FACTORY)
        .addModifiers(ABSTRACT)
        .superclass(superclass)
    }

    return builder
      .addAnnotation(Subcomponent.Factory::class)
      .build()
  }

  private fun generateDaggerModule(
    factoryReference: ClassReference
  ): TypeSpec {
    // This Dagger module will allow injecting the factory instance.
    return TypeSpec
      .classBuilder(SUBCOMPONENT_MODULE)
      .addModifiers(ABSTRACT)
      .addAnnotation(Module::class)
      .addFunction(
        FunSpec
          .builder("bindSubcomponentFactory")
          .addAnnotation(Binds::class.java)
          .addModifiers(ABSTRACT)
          .addParameter("factory", ClassName.bestGuess(SUBCOMPONENT_FACTORY))
          .returns(factoryReference.asClassName())
          .build()
      )
      .build()
  }

  private fun generateParentComponent(
    contribution: Contribution,
    generatedAnvilSubcomponent: ClassId,
    factoryClass: FactoryClassHolder?,
  ): TypeSpec {
    val parentComponentInterface = findParentComponentInterface(
      contribution, factoryClass?.originalReference?.fqName
    )

    return TypeSpec
      .interfaceBuilder(PARENT_COMPONENT)
      .apply {
        if (parentComponentInterface != null) {
          addSuperinterface(parentComponentInterface.componentInterface)
        }
      }
      .addFunction(
        FunSpec
          .builder(
            name = parentComponentInterface
              ?.functionName
              ?: factoryClass?.let { "create${it.originalReference.fqName.shortName()}" }
              ?: "create${generatedAnvilSubcomponent.relativeClassName}"
          )
          .addModifiers(ABSTRACT)
          .apply {
            if (parentComponentInterface != null) {
              addModifiers(OVERRIDE)
            }

            if (factoryClass != null) {
              returns(factoryClass.generatedFactoryName)
            } else {
              returns(generatedAnvilSubcomponent.asClassName())
            }
          }
          .build()
      )
      .build()
  }

  private fun findParentComponentInterface(
    contribution: Contribution,
    factoryClass: FqName?
  ): ParentComponentInterfaceHolder? {
    val contributionFqName = contribution.clazzFqName

    val contributedInnerComponentInterfaces = contribution.classReference
      .innerClasses()
      .filter { it.isInterface() }
      .filter { classReference ->
        classReference.annotations
          .any {
            it.fqName == contributesToFqName && it.scope().fqName == contribution.parentScope
          }
      }
      .toList()

    val componentInterface = when (contributedInnerComponentInterfaces.size) {
      0 -> return null
      1 -> contributedInnerComponentInterfaces[0]
      else -> throw AnvilCompilationExceptionClassReference(
        classReference = contribution.classReference,
        message = "Expected zero or one parent component interface within " +
          "$contributionFqName being contributed to the parent scope."
      )
    }

    val functions = componentInterface.functions
      .filter { it.isAbstract() && it.visibility() == PUBLIC }
      .filter {
        val returnType = it.returnType().fqName
        returnType == contributionFqName || (factoryClass != null && returnType == factoryClass)
      }

    val function = when (functions.size) {
      0 -> return null
      1 -> functions[0]
      else -> throw AnvilCompilationExceptionClassReference(
        classReference = contribution.classReference,
        message = "Expected zero or one function returning the subcomponent $contributionFqName."
      )
    }

    return ParentComponentInterfaceHolder(componentInterface, function)
  }

  private fun findFactoryClass(
    contribution: Contribution,
    generatedAnvilSubcomponent: ClassId
  ): FactoryClassHolder? {
    val contributionFqName = contribution.clazzFqName

    val contributedFactories = contribution.classReference
      .innerClasses()
      .filter { classReference ->
        classReference.isAnnotatedWith(contributesSubcomponentFactoryFqName)
      }
      .onEach { factory ->
        if (!factory.isInterface() && !factory.isAbstract()) {
          throw AnvilCompilationExceptionClassReference(
            classReference = factory,
            message = "A factory must be an interface or an abstract class."
          )
        }

        val createComponentFunctions = factory.functions
          .filter { it.isAbstract() }
          .filter { it.returnType().fqName == contributionFqName }

        if (createComponentFunctions.size != 1) {
          throw AnvilCompilationExceptionClassReference(
            classReference = factory,
            message = "A factory must have exactly one abstract function returning the " +
              "subcomponent $contributionFqName."
          )
        }
      }
      .toList()

    val classReference = when (contributedFactories.size) {
      0 -> return null
      1 -> contributedFactories[0]
      else -> throw AnvilCompilationExceptionClassReference(
        classReference = contribution.classReference,
        message = "Expected zero or one factory within $contributionFqName."
      )
    }

    return FactoryClassHolder(
      originalReference = classReference,
      generatedFactoryName = generatedAnvilSubcomponent
        .createNestedClassId(Name.identifier(SUBCOMPONENT_FACTORY))
        .asClassName()
    )
  }

  private fun checkReplacedSubcomponentWasNotAlreadyGenerated(
    contributedReference: ClassReference,
    replacedReferences: List<ClassReference>
  ) {
    replacedReferences.forEach { replacedReference ->
      if (processedEvents.any { it.contribution.classReference == replacedReference }) {
        throw AnvilCompilationExceptionClassReference(
          classReference = contributedReference,
          message = "${contributedReference.fqName} tries to replace " +
            "${replacedReference.fqName}, but the code for ${replacedReference.fqName} was " +
            "already generated. This is not supported."
        )
      }
    }
  }

  private fun populateInitialContributions(
    module: ModuleDescriptor
  ) {
    // Find all contributed subcomponents from precompiled dependencies and generate the
    // necessary code eventually if there's a trigger.
    contributions += classScanner
      .findContributedClasses(
        module = module,
        packageName = HINT_SUBCOMPONENTS_PACKAGE_PREFIX,
        annotation = contributesSubcomponentFqName,
        scope = null
      )
      .map { descriptor ->
        val annotation = descriptor.annotation(contributesSubcomponentFqName)

        val modules = (annotation.getAnnotationValue("modules") as? ArrayValue)
          ?.value
          ?.map { it.argumentType(module).fqName() }
          ?: emptyList()

        val exclude = (annotation.getAnnotationValue("exclude") as? ArrayValue)
          ?.value
          ?.map { it.argumentType(module).fqName() }
          ?: emptyList()

        Contribution(
          classReference = descriptor.toClassReference(module),
          scope = annotation.scope(module).fqNameSafe,
          parentScope = annotation.parentScope(module).fqNameSafe,
          modules = modules,
          exclude = exclude
        )
      }

    // Find all replaced subcomponents from precompiled dependencies.
    replacedReferences += contributions
      .flatMap {
        it.classReference.replaces(contributesSubcomponentFqName)
      }
  }

  private companion object {
    val generationTrigger = setOf(
      mergeComponentFqName,
      mergeSubcomponentFqName,
      // Note that we don't include @MergeModules, because we would potentially generate
      // components twice. @MergeInterfaces and @MergeModules are doing separately what
      // @MergeComponent is doing at once.
      mergeInterfacesFqName,
    )
  }

  private class Trigger(
    val clazz: KtClassOrObject,
    val scope: FqName,
    val exclusions: List<FqName>
  ) {
    val clazzFqName = clazz.requireFqName()

    override fun toString(): String {
      return "Trigger(clazz=$clazzFqName, scope=$scope)"
    }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as Trigger

      if (scope != other.scope) return false
      if (clazzFqName != other.clazzFqName) return false

      return true
    }

    override fun hashCode(): Int {
      var result = scope.hashCode()
      result = 31 * result + clazzFqName.hashCode()
      return result
    }
  }

  private class Contribution(
    val classReference: ClassReference,
    val scope: FqName,
    val parentScope: FqName,
    val modules: List<FqName>,
    val exclude: List<FqName>
  ) {
    val clazzFqName = classReference.fqName

    override fun toString(): String {
      return "Contribution(class=$classReference, scope=$scope, parentScope=$parentScope)"
    }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as Contribution

      if (scope != other.scope) return false
      if (parentScope != other.parentScope) return false
      if (clazzFqName != other.clazzFqName) return false

      return true
    }

    override fun hashCode(): Int {
      var result = scope.hashCode()
      result = 31 * result + parentScope.hashCode()
      result = 31 * result + clazzFqName.hashCode()
      return result
    }
  }

  private data class GenerateCodeEvent(
    val trigger: Trigger,
    val contribution: Contribution
  ) {
    val generatedAnvilSubcomponent = contribution.classReference.classId
      .generatedAnvilSubcomponent(trigger.clazz.toClassId())
  }

  private class ParentComponentInterfaceHolder(
    componentInterface: ClassReference,
    function: FunctionReference
  ) {
    val componentInterface = componentInterface.asClassName()
    val functionName = function.fqName.shortName().asString()
  }

  private class FactoryClassHolder(
    val originalReference: ClassReference,
    val generatedFactoryName: ClassName
  )
}

/**
 * Returns the Anvil subcomponent that will be generated for a class annotated with
 * `ContributesSubcomponent`.
 */
internal fun ClassId.generatedAnvilSubcomponent(parentClass: ClassId): ClassId {
  // Encode the parent class name in the package rather than the class name itself. This avoids
  // issues with too long class names. Dagger will generate subcomponents as inner classes and
  // deep hierarchies will be a problem. See https://github.com/google/dagger/issues/421
  //
  // To avoid potential issues, encode the parent class name in the package and keep the generated
  // class name short.
  val parentClassPackageSuffix = if (parentClass.relativeClassName.isRoot) {
    "root"
  } else {
    parentClass.relativeClassName.asString().lowercase()
  }

  // Note that use the package from the parent class and not our actual subcomponent. This is
  // necessary to avoid conflicts as well.
  val parentPackageName = parentClass.packageFqName
    .safePackageString(dotPrefix = false, dotSuffix = true) + parentClassPackageSuffix

  val packageFqName = if (parentPackageName.startsWith(COMPONENT_PACKAGE_PREFIX)) {
    // This happens if the parent is generated by Anvil itself. Avoid nesting too deeply.
    parentPackageName
  } else {
    "$COMPONENT_PACKAGE_PREFIX.$parentPackageName"
  }

  val relativeClassName = relativeClassName.generateClassName(suffix = ANVIL_SUBCOMPONENT_SUFFIX)

  return ClassId(FqName(packageFqName), relativeClassName.relativeClassName, false)
}
