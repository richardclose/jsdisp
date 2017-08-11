package org.phasanix.jsdisp

import play.api.libs.json._
import language.experimental.macros

abstract class JsonDispatcherBase {
  /**
    * Return Javascript function, which creates a proxy for invoking
    * the methods via XHR.
    */
  def jsProxy: String

  // Called from generated code.
  protected def fromJs[B : Reads](value: JsValue): B = {
    Json.fromJson[B](value).fold (
      errs => {
        println(s"err. value=$value")
        throw new Exception(errs.flatMap(_._2.map(_.message)).mkString(","))
      },
      valid =>
        valid
    )
  }
}

/**
  * Dispatcher for JsRpc method calls
  * Argument list must be a javascript array,
  * or single value if the called method has one argument.
  * @tparam A wrapped type
  */
abstract class JsonDispatcher[A] extends JsonDispatcherBase {
  /**
    * Invoke method on the wrapped object, converting argument appropriately.
    * @param methodName name of method to invoke.
    * @param args JsArray of arguments
    * @return method return value, converted to JsValue
    */
  def dispatch(methodName: String, args: JsArray): JsValue
}

abstract class JsonDispatcherWithImplicit[A, I] extends JsonDispatcherBase {
  /**
    * Invoke method on the wrapped object, converting argument appropriately.
    * @param methodName name of method to invoke.
    * @param args JsArray of arguments
    * @return method return value, converted to JsValue
    */
  def dispatch(methodName: String, args: JsArray)(implicit i: I): JsValue
}


object JsonDispatcher {

  import reflect.macros.blackbox

  class WithImplicit[I] {
    def apply[A](obj: A): JsonDispatcherWithImplicit[A, I] = macro createWithImplicit_impl[A, I]
  }

  /**
    * Create a JsonDispatcher instance for the given object, supplying a single implicit
    * argument to dispatch.
    *
    */
  def createWithImplicit[I]: WithImplicit[I] = new WithImplicit[I]

  /**
    * Create a JsonDispatcher instance for the given object.
    * @param obj
    * @tparam A
    * @return
    */
  def create[A](obj: A): JsonDispatcher[A] = macro create_impl[A]


  private def methodInfo(c: blackbox.Context)(sym: c.universe.Symbol): Option[(c.universe.MethodSymbol, Seq[c.universe.Symbol], Boolean)] = {
    import c.universe._

    if (sym.isMethod && !sym.isConstructor) {
      val m = sym.asMethod
      if (m.returnType =:= typeOf[Unit]) {
        None
      } else if (m.paramLists.length == 1) {
        Some((m, m.paramLists.head, false))
      } else if (m.paramLists.length == 2 &&
        m.paramLists.last.head.isImplicit &&
        m.paramLists.last.length == 1
      ) {
        Some(m, m.paramLists.head, true)
      } else {
        None
      }
    } else {
      None
    }
  }

  private def makeJsProxy(c: blackbox.Context)(tpe: c.universe.Type): String = {
    val jsProxies = for {
      decl <- tpe.decls
      (m, args, _) <- methodInfo(c)(decl)
    } yield {

      val argNames = args.filter(_.isTerm).map(_.asTerm.name.toString)

      s"""this.${m.name.toString} = function(${argNames.mkString(",")}) {
         |if (arguments.length != ${argNames.length})
         |  throw 'wrong number of arguments. Expected ${argNames.length}, got ' + arguments.length;
         |return this._wrap(this, '${m.name.toString}', JSON.stringify(Array.from(arguments)));
         |}
       """.stripMargin
    }

    javascriptSource(tpe.typeSymbol.name.toString, jsProxies.toSeq)
  }

  private def makeCaseStatements[A: c.universe.WeakTypeTag](c: blackbox.Context)(obj: c.universe.Expr[A]) = {
    import c.universe._

    val tpe = weakTypeOf[A]

    (for {
      decl <- tpe.decls
      (m, argList, hasImplicit) <- methodInfo(c)(decl)
    } yield {
      val name = m.name.toString

      val argExprs = argList.zipWithIndex.map { case (arg, i) =>
        val idx = Constant(i)
        q"fromJs[${arg.typeSignature}](args.value($idx))"
      }


      c.info(NoPosition, s"binding $name(${argList.map(_.toString).mkString(",")})", force = true)

      val len = argList.length

      if (hasImplicit) {
        cq""" $name => {
          if (args.value.length != $len)
            throw new Exception("expected " + $len + " arguments, found " + args.value.length)
          Json.toJson( $obj.${m.name}(..$argExprs)(imp) )
        }
          """
      } else {
        cq""" $name => {
          if (args.value.length != $len)
            throw new Exception("expected " + $len + " arguments, found " + args.value.length)
          Json.toJson($obj.${m.name}(..$argExprs))
        }
          """
      }

    }) ++ Seq(cq""" _ => throw new Exception(s"no method " + methodName) """)
  }

  def create_impl[A : c.WeakTypeTag](c: blackbox.Context)(obj: c.Expr[A]) = {
    import c.universe._

    val tpe = weakTypeOf[A]

    val cases = makeCaseStatements(c)(obj)

    val jsProxy = makeJsProxy(c)(tpe)

    q"""
        import play.api.libs.json._
        new org.phasanix.jsdisp.JsonDispatcher[$tpe] {
          def dispatch(methodName: String, args: JsArray): JsValue = {
            methodName match {
              case ..$cases
            }
          }

          def jsProxy: String = $jsProxy
        }
      """
  }

  def createWithImplicit_impl[A : c.WeakTypeTag, I : c.WeakTypeTag](c: blackbox.Context)(obj: c.Expr[A]) = {
    import c.universe._

    val tpe = weakTypeOf[A]
    val impTpe = weakTypeOf[I]

    val cases = makeCaseStatements(c)(obj)

    val jsProxy = makeJsProxy(c)(tpe)

    q"""
        import play.api.libs.json._
        new org.phasanix.jsdisp.JsonDispatcherWithImplicit[$tpe, $impTpe] {
          def dispatch(methodName: String, args: JsArray)(implicit imp: $impTpe): JsValue = {
            methodName match {
              case ..$cases
            }
          }

          def jsProxy: String = $jsProxy
        }
      """
  }

  private def javascriptSource(name: String, jsMethods: Seq[String]) = {
    s"""
// Return a Javascript proxy.
function(uri) {

  function WrappedCall(proxy, method, serializedArgs) {
    var self = this;
    this._onError = proxy.onError;
    this._onSuccess = proxy.onSuccess;

    this._onComplete = function(xhr) {
      if (xhr.status == 200) {
        try {
          this._onSuccess(JSON.parse(xhr.response), xhr);
        } catch (ex) {
          this._onError(ex, xhr);
        }
      } else {
        this._onError(xhr.responseText, xhr);
      }
    }

    this.onComplete = function(fn) {
      this._onComplete = fn;
      return this;
    }

    this.onSuccess = function(fn) {
      this._onSuccess = fn;
      return this;
    }

    this.onError = function(fn) {
      this._onError = fn;
      return this;
    }

    this.go = function() {
      var xhr = new XMLHttpRequest();
      xhr.open('POST', uri + '/' + method, true);

      xhr.onreadystatechange = function() {
        if (xhr.readyState == XMLHttpRequest.DONE) {
          self._onComplete(xhr);
        }
      }

      xhr.setRequestHeader('Content-Type','application/json');
      xhr.send(serializedArgs);
    }
  }

  function Proxy() {
    this._wrap = function(proxy, method, serializedArgs) {
      return new WrappedCall(proxy, method, serializedArgs);
    }

    this.onSuccess = function(response, xhr) {}

    this.onError = function(message, xhr) {
      var evt = new CustomEvent('apierror', {detail: {status: xhr.status, message: message}});
      document.dispatch(evt);
    }

   ${jsMethods.mkString("\n\n  ")}
  }

  return new Proxy();
}
"""
  }

}
