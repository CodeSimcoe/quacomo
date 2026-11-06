package com.codesimcoe.quacomo.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects a local service provider, or a generated remote client when remote is set.
 * Local bindings require exactly one provider; remote bindings require the remoting extension.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface UsedComponentServices {
  /**
   * Named remote destination(s); empty means local delivery.
   */
  String remote() default "";
}
