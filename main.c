#include <stdio.h>
#include <stdlib.h>
 

extern FILE * yyin;
extern char *yytext;

FILE * fichero = (FILE *) 0;

main (int argc, char **argv)
{
	if   (argc > 1) {     
		int i = 1;
		//yyin = fopen("minimo.int", "r");
		do {
		    yyin = fopen(argv[i], "r");  
		    if (yyin != NULL){
		    printf("estamos en main, acabamos de abrir el programa fuente, ahora vamos a compilarlo.....\n");
			   yyparse();
			   fclose(yyin);
			   i++;
			}
			} while (i !=argc );  //do
		     } //si hay argumentos en la linea de comandos 
			else {
				printf("debe escribir el nombre del programa que quiere analizar.\n"); exit(1); }

/*
		i = 1;
		do {
			execut(pila_programas[i-1]);
			i++;
		} while (i != argc);

                exit (0);

	}

	else
			printf("usar: miprograma.exe nombreprograma [nombreprograma....]\n");

*/

}


