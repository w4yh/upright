CREATE TABLE TEST3a(A INT, B FLOAT); 
DESCRIBE TEST3a; 
CREATE TABLE TEST3b(A ARRAY<INT>, B FLOAT, C MAP<FLOAT, INT>); 
DESCRIBE TEST3b; 
SHOW TABLES;
EXPLAIN
ALTER TABLE TEST3b ADD COLUMNS (X FLOAT);
ALTER TABLE TEST3b ADD COLUMNS (X FLOAT);
DESCRIBE TEST3b; 
EXPLAIN
ALTER TABLE TEST3b RENAME TO TEST3c;
ALTER TABLE TEST3b RENAME TO TEST3c;
DESCRIBE TEST3c; 
SHOW TABLES;
DROP TABLE TEST3c;
DROP TABLE TEST3a;
