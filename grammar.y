%start ROOT

  
   %token PLUS
   
   %token ASSIGN
   %token SEMICOLON
 
   %token PRINT
   %token NUMBER
   %token NAME

   %%        

   ROOT:
     stmtseq { execute($1); } 
   ;

   stmtseq:
     stmtseq SEMICOLON statement { $$ = seq($1, $3); }
   | statement { $$ = $1; }
   ;

   statement:
     designator ASSIGN expression { $$ = assignment($1, $3); } 
   | PRINT expression { $$ = print($2); } 
   
   ;

   expression:
     expr2 { $$ = $1; } 
   ;

   expr2:
     expr3 { $$ == $1; }
   | expr2 PLUS expr3 { $$ = plus($1, $3); }       
   ;

   expr3:
     expr4 { $$ = $1; }
    
   ;

   expr4:
     PLUS expr4 { $$ = $2; }
   | NUMBER { $$ = number($1); }
   | designator { $$ = $1; }
   ;

   designator:
     NAME { $$ = name($1); }
   ;








