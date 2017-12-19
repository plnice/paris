package com.airbnb.paris.processor.writers

import com.airbnb.paris.processor.*
import com.airbnb.paris.processor.framework.*
import com.airbnb.paris.processor.models.*
import com.airbnb.paris.processor.utils.*
import com.squareup.javapoet.*

internal class StyleAppliersJavaFile(processor: ParisProcessor, styleablesTree: StyleablesTree, styleableInfo: StyleableInfo)
    : SkyJavaClass<ParisProcessor>(processor, block = {

    addAnnotation(AndroidClassNames.UI_THREAD)
    public()
    final()
    superclass(ParameterizedTypeName.get(STYLE_APPLIER_CLASS_NAME, TypeName.get(styleableInfo.elementType), TypeName.get(styleableInfo.viewElementType)))

    constructor {
        public()
        addParameter(TypeName.get(styleableInfo.viewElementType), "view")
        if (styleableInfo.elementType == styleableInfo.viewElementType) {
            addStatement("super(view)")
        } else {
            // Different types means this style applier uses a proxy
            addStatement("super(new \$T(view))", styleableInfo.elementType)
        }
    }

    // If the view type is "View" then there is no parent
    var parentStyleApplierClassName: ClassName? = null
    if (!isSameType(elements.VIEW_TYPE.asType(), styleableInfo.viewElementType)) {
        parentStyleApplierClassName = styleablesTree.findStyleApplier(
                styleableInfo.viewElementType.asTypeElement().superclass.asTypeElement())
        method("applyParent") {
            override()
            protected()
            addParameter(STYLE_CLASS_NAME, "style")
            addStatement("\$T applier = new \$T(getView())", parentStyleApplierClassName, parentStyleApplierClassName)
            addStatement("applier.setDebugListener(getDebugListener())")
            addStatement("applier.apply(style)")
        }
    }

    if (!styleableInfo.styleableResourceName.isEmpty()) {
        method("attributes") {
            override()
            protected()
            returns(ArrayTypeName.of(Integer.TYPE))
            addStatement("return \$T.styleable.\$L", RFinder.element, styleableInfo.styleableResourceName)
        }

        val attrsWithDefaultValue = styleableInfo.attrs
                .filter { it.defaultValueResId != null }
                .map { it.styleableResId}
                .toSet()
        if (attrsWithDefaultValue.isNotEmpty()) {
            method("attributesWithDefaultValue") {
                override()
                public()
                returns(ArrayTypeName.of(Integer.TYPE))
                addCode("return new int[] {")
                for (attr in attrsWithDefaultValue) {
                    addCode("\$L,", attr.code)
                }
                addCode("};\n")
            }
        }

        method("processStyleableFields") {
            override()
            protected()
            addParameter(STYLE_CLASS_NAME, "style")
            addParameter(TYPED_ARRAY_WRAPPER_CLASS_NAME, "a")
            addStatement("\$T res = getView().getContext().getResources()", AndroidClassNames.RESOURCES)

            for (styleableChild in styleableInfo.styleableChildren) {
                controlFlow("if (a.hasValue(\$L))", styleableChild.styleableResId.code) {
                    addStatement("\$N().apply(\$L)", styleableChild.elementName, Format.STYLE.typedArrayMethodCode("a", styleableChild.styleableResId.code))
                }

                if (styleableChild.defaultValueResId != null) {
                    controlFlow("else") {
                        addStatement("\$N().apply(\$L)", styleableChild.elementName, Format.STYLE.resourcesMethodCode("res", styleableChild.defaultValueResId.code))
                    }
                }
            }
        }

        method("processAttributes") {
            override()
            protected()
            addParameter(STYLE_CLASS_NAME, "style")
            addParameter(TYPED_ARRAY_WRAPPER_CLASS_NAME, "a")
            addStatement("\$T res = getView().getContext().getResources()", AndroidClassNames.RESOURCES)

            for (beforeStyle in styleableInfo.beforeStyles) {
                addStatement("getProxy().\$N(style)", beforeStyle.elementName)
            }

            for (attr in styleableInfo.attrs) {
                controlFlow("if (a.hasValue(\$L))", attr.styleableResId.code) {
                    addStatement("getProxy().\$N(\$L)", attr.elementName, attr.targetFormat.typedArrayMethodCode("a", attr.styleableResId.code))
                }

                if (attr.defaultValueResId != null) {
                    controlFlow("else") {
                        addStatement("getProxy().\$N(\$L)", attr.elementName, attr.targetFormat.resourcesMethodCode("res", attr.defaultValueResId.code))
                    }
                }
            }

            for (afterStyle in styleableInfo.afterStyles) {
                addStatement("getProxy().\$N(style)", afterStyle.elementName)
            }
        }

    }

    val styleApplierClassName = styleableInfo.styleApplierClassName()

    addType(BaseStyleBuilderJavaClass(processor, parentStyleApplierClassName, RFinder.element!!.className, styleablesTree, styleableInfo).build())
    val styleBuilderClassName = styleApplierClassName.nestedClass("StyleBuilder")
    addType(StyleBuilderJavaClass(processor, styleableInfo).build())

    // builder() method
    method("builder") {
        public()
        returns(styleBuilderClassName)
        addStatement("return new \$T(this)", styleBuilderClassName)
    }

    for (styleableChildInfo in styleableInfo.styleableChildren) {
        val subStyleApplierClassName = styleablesTree.findStyleApplier(
                styleableChildInfo.elementType.asTypeElement())
        method(styleableChildInfo.elementName) {
            public()
            returns(subStyleApplierClassName)
            addStatement("\$T subApplier = new \$T(getProxy().\$N)", subStyleApplierClassName, subStyleApplierClassName, styleableChildInfo.elementName)
            addStatement("subApplier.setDebugListener(getDebugListener())")
            addStatement("return subApplier", subStyleApplierClassName, styleableChildInfo.elementName)
        }
    }

    for (styleInfo in styleableInfo.styles) {
        method("apply${styleInfo.formattedName}") {
            addJavadoc(styleInfo.javadoc)
            public()

            when (styleInfo.elementKind) {
                StyleInfo.Kind.FIELD -> {
                    addStatement("apply(\$T.\$L)", styleInfo.enclosingElement, styleInfo.elementName)
                }
                StyleInfo.Kind.METHOD -> {
                    addStatement("\$T builder = new \$T()", styleBuilderClassName, styleBuilderClassName)
                            .addStatement("\$T.\$L(builder)", styleInfo.enclosingElement, styleInfo.elementName)
                            .addStatement("apply(builder.build())")
                }
                StyleInfo.Kind.STYLE_RES -> {
                    addStatement("apply(\$L)", styleInfo.styleResourceCode)
                }
                StyleInfo.Kind.EMPTY -> {
                    // Do nothing!
                }
            }
        }
    }
}) {

    init {
        packageName = styleableInfo.styleApplierClassName().packageName()
        name = styleableInfo.styleApplierClassName().simpleName()
    }
}
