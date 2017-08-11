# jsdisp: JSON wrapper generator for remoting

## Purpose

`JsonDispatcher` is a proxy generator for dynamically dispatching method calls to
scala objects, where the arguments and return value are encoded as JSON. The
generated proxy has a `dispatch` method, which receives an array of arguments as JSON
and returns a JSON value. It also generates a Javascript proxy, suitable for including
in a browser-side script, which invokes the proxied methods via Ajax.

This is not typesafe on the client side, since the methods are invoked from Javascript. 
ScalaJS + `autowire` is much better from this point of view, especially if you have a lot
of client-side logic implemented in ScalaJS.
The advantages of `JsonDispatcher` are: 
- Simpler for casual use as it doesn't need a multi-project SBT configuration, and 
does not incur the script size overhead of ScalaJS.
- It addresses a very common use case by providing a mechanism for supplying server-side 
parameters via an implicit argument to `dispatch`. A typical use of this is supplying 
an authenticated user.

## Usage

On the server side, wrap an object, with a dispatcher (this illustration is for the 
Play framework):

```scala
class ApiController @Inject()(args ...) extends AbstractController(args) {
  private val dispatcher = JsonDispatcher.createWithImplicit[RequestHeader](Api)
  def dispatch(method: String) = Action(parse.json) { implicit request =>
    val ret = dispatcher.invoke(method, request.body)
    Ok(ret)
  }
}

object Api {
  def strings(s: String, count: Int)(implicit req: RequestHeader): Seq[String] = 
    Seq.tabulate(count) { i =>  s + i.toString + req.method }
}
```

In this example, the `routes` file has an an entry like:
```
/api/:method  -> controllers.ApiController(method: String)
```

On the client side, the generated proxy is used to call the API method. This is a 
Twirl template:

```
@(dispatcher: JsonDispatcherBase)
<body>
<input id="result" type="text"></input>
<script type="text/javascript">
var apiProxy = (@dispatcher.proxy)('/api');

function strings() {
    apiProxy.strings('hey', 5)
        .onSuccess(function(data) {
            document.getElementById('result').value = data.join(',')
        })
        .go();
}
</script>
</body>
```

The proxy has a couple of other features: an `onSuccess` hook which is invoked for all 
API calls, and a default `onError` function implementation which raises `CustomEvents`.

## Future development

The dependency on the JSON codec (Play JSON) should be replaced with an abstraction.