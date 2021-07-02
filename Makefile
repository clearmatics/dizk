
compile:
	mvn compile

clean:
	mvn clean

package:
	mvn package -Dmaven.test.skip=true

test check:
	mvn test
