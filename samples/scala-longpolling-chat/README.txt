To run this sample:

  mvn exec:java

and go to the URL using Firefox:

  http://localhost:9999/app/

or in one terminal window use curl:

  curl http://localhost:9999/app/chat

and in another terminal use curl:

  curl -d message=HELLO http://localhost:9999/app/chat

See:

  http://blogs.sun.com/sandoz/entry/simple_long_polling_in_scala 

for more details about this sample.
