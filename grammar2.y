%start ROOT

  
   %token PLUS
   
   %token ASSIGN
   %token SEMICOLON
 
   %token PRINT
   %token NUMBER
   %token NAME

   %%        

   ROOT:
     stmtseq { printf("se ha compilado todo el programa\n"); } 
   ;

   stmtseq:
     stmtseq SEMICOLON statement { printf("una lista de instrucciones ';' instruccion\n") ;  }
   | statement { printf ("se ha encontrado una instruccion\n"); }
   ;

   statement:
     designator ASSIGN expression { printf("aqui se quiere asignar una expresion a un designador\n"); } 
   | PRINT expression { printf("imprimir una expresion\n"); } 
   
   ;

   expression:
     expr2 { $$ = $1; }     
   ;

   expr2:
     expr3 { $$ == $1; }
   | expr2 PLUS expr3 { $$ = $1; printf("sumar 2 expresiones"); }       
   ;

   expr3:
     expr4 { $$ = $1; }
    
   ;

   expr4:
     NUMBER { $$ = $1; printf("numero\n"); }
   | designator { $$ = $1; printf("variable\n"); }
   ;

   designator:
     NAME { $$ = $1; printf ("nombre\n"); }
   ;








