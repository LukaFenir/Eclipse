
DiskSim.class: DiskSim.java BRequest.java DSched.java
	@javac DiskSim.java BRequest.java DSched.java

.PHONY: clean
clean:
	@rm -f *.class

