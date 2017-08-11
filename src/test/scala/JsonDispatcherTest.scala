import org.phasanix.jsdisp.JsonDispatcher
import play.api.libs.json.{JsArray, JsNull, JsString, Json}
import utest._

object JsonDispatcherTest extends TestSuite {

  case class Person(first: String, last: String, age: Double)

  implicit val personFmt = Json.format[Person]

  object Test {
    def one(a: String, b: Int): Seq[String] =
      Seq.tabulate(b) { i => s"$i: $a" }

    def person(last: String): Person =
      Person("John", last, 21.0)
  }

  object TestWithImplicit {
    def str(d: Double)(implicit person: Person): String = s"${person.last} - $d"
  }


  val tests = this {

    val dispTest = JsonDispatcher.create(Test)

    "invoke method"- {

      val ret = dispTest.dispatch("one", Json.arr(Json.toJson("bang"), Json.toJson(3)))

      Json.fromJson[Seq[String]](ret).map { arr =>
        arr.length ==> 3
      }.getOrElse(assert(false))
    }

    "method returning custom type"- {
      val p = dispTest.dispatch("person", Json.arr(Json.toJson("Smith")))
      val person = Json.fromJson[Person](p)
      person.get.last ==> "Smith"
    }

    "method with implicit"- {
      val dispatcher = JsonDispatcher.createWithImplicit[Person](TestWithImplicit)
      implicit val person = Person("Fred", "Wilson", 40.0)
      dispatcher.dispatch("str", Json.arr(Json.toJson(10.0))) match {
        case js: JsString => js.value ==> "Wilson - 10.0"
        case _ => assert(false)
      }
    }

    "wrong number of arguments"- {
      intercept[Exception] {
        dispTest.dispatch("one", Json.arr(JsNull))
      }.getMessage ==> "expected 2 arguments, found 1"
    }

    "wrong argument type"-{
      val ex = intercept[Exception] {
        dispTest.dispatch("one", Json.arr(Json.toJson("x"), Json.toJson("y")))
      }.getMessage ==> "error.expected.jsnumber"
    }

  }
}