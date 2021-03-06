%{
   #include "y.tab.h"
   extern int yylval;
%}

%%
"+"      { return PLUS; }
":="     { return ASSIGN; }
";"      { return SEMICOLON; }      

"PRINT"  { return PRINT; }

[0-9]+   { yylval = atoi(yytext); return NUMBER; }
[a-z]    { yylval = yytext[0] - 'a'; return NAME; }   

\        { ; }
\n       { ; }
\t       { ; }
"//".*\n { ; }


.        { printf ("illegal token"); }

%%

#ifndef yywrap
yywrap() { return 1; }
#endif


yyerror() {
   printf ("estamos en yyerror\n");
}


