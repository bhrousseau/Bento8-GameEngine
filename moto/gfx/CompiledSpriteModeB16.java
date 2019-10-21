package moto.gfx;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.ColorModel;
import java.awt.Color;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.util.ArrayList;
import java.util.List;

// TODO
// Ajout génération complete du code
// Modification de l'algo pour LD à la place du PUL
// Calcul nb cycles
// Ajout gestion de l'avance par 1 pixel
// Ajout methode d'effacement du fond d'ecran
// Ajout gestion bit depth 4+Transparence ou bit depth 8+T
// Ajout mode non indexé avec Transparence
// Ajout GUI + gestion resize avec aspect ratio
// Ajout diminution de palette et selection manuelle de la couleur Transparente
// Mode batch
// Gestion Raster Effect
// Ajout priority flag (avant plan ou arriere plan)
// Gestion flip horizontal ou vertical
// rotate, scaling, warping
// slice (tranche a gauche et tranche a droite) effect d'entrelacement explosion : ou apparition en empilement de lignes
// effet de chaleur en variant les retours de ligne
// clignotement

public class CompiledSpriteModeB16
{
	// Convertisseur d'image PNG en "Compiled Sprite"
	// Thomson MO5NR, MO6, TO8, TO9 et TO9+
	// Mode 160x200 en seize couleurs sans contraintes

	// Image
	BufferedImage image;
	ColorModel colorModel;
	String spriteName;
	int width;
	int height;
	byte[] pixels;

	// Calcul
	ArrayList<ArrayList<ArrayList<Integer>>> regCombos = new ArrayList<ArrayList<ArrayList<Integer>>>();
	
	// Code
	List<String> spriteCode1 = new ArrayList<String>();
	List<String> spriteCode2 = new ArrayList<String>();
	List<String> spriteData1 = new ArrayList<String>();
	List<String> spriteData2 = new ArrayList<String>();
	
	String posLabel;
	String drawLabel;
	String dataLabel;
	
	public CompiledSpriteModeB16(String file)
	{
		try
		{
			// Construction des combinaisons des 5 registres pour le PSH
			ComputeRegCombos();	

			// Lecture de l'image a traiter
			image = ImageIO.read(new File(file));
			width = image.getWidth();
			height = image.getHeight();
			colorModel = image.getColorModel();
			int pixelSize = colorModel.getPixelSize();
			int numComponents = colorModel.getNumComponents();
			spriteName = removeExtension(file).toUpperCase();
			
			// Initialisation du code statique
			posLabel = "POS_"+spriteName;
			drawLabel = "DRAW_"+spriteName;
			dataLabel = "DATA_"+spriteName;
			
			//System.out.println("Type image:"+image.getType());
			// Contrôle du format d'image
			if (width<=160 && height<=200 && pixelSize==8) { // && numComponents==3
				
				// Si la largeur d'image est impaire, on ajoute une colonne de pixel transparent a gauche de l'image
				if (width % 2 != 0) {
					pixels = new byte[(width+1)*height];
					for (int iSource=0,iCol=0,iDest=0; iDest<(width+1)*height; iDest++) {
						if (iCol == 0) {
							pixels[iDest] = 16;
						} else {
							pixels[iDest] = (byte) ((DataBufferByte) image.getRaster().getDataBuffer()).getElem(iSource);
							iSource++;
						}
						//System.out.println(pixels[iDest]);
						iCol++;
						if (iCol == width+1) {
							iCol=0;
						}
					}
					width = width+1;
				} else { // Sinon construction du tableau de pixels à partir de l'image	
					pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
				}
				
				// Génération du code Assembleur
				generateCode();
			}
			else {
				// Présente les formats acceptés à l'utilisateur en cas de fichier d'entrée incompatible
				System.out.println("Le format de fichier de "+file+" n'est pas supporté.");
				System.out.println("Resolution: "+width+"x"+height+"px (doit être inférieur ou égal à 160x200)");
				System.out.println("Taille pixel:  "+pixelSize+"Bytes (doit être 8)");
				//System.out.println("Nombre de composants: "+numComponents+" (doit être 3)");
			}
		} 
		catch (Exception e)
		{
			e.printStackTrace(); 
			System.out.println(e); 
		}
	}

	public void generateCode() {
		// Génération du code source pour l'écriture des images
		// TODO *** Génération d'un fichier en sortie en remplacement du System.out

		List<String>[] sprite = generateCodeArray(1);
		spriteCode1 = sprite[0];
		spriteData1 = sprite[1];
		sprite = generateCodeArray(3);
		spriteCode2 = sprite[0];
		spriteData2 = sprite[1];		
		return;
	}

	public List<String>[] generateCodeArray(int pos) {
		int col = width;
		int fcol = 0;
		int row = height;
		int rowDeb = 0;
		int bitIndex = 0;
		int chunk = 1;
		int doubleFwd = 0;
		int leas = 0;
		int fleas = 0;
		int fpixel = 0;
		int frpixel = 0;
		boolean leftAlphaPxl = false;
		boolean rightAlphaPxl = false;

		List<String> spriteCode = new ArrayList<String>();
		ArrayList<String> fdbBytes = new ArrayList<String>();
		String fdbBytesResult = new String();
		String fdbBytesResultLigne = new String();
		String[] pulBytesOld = {"", "", "", "", ""};
		ArrayList<Integer> motif = new ArrayList<Integer>();	

		// **************************************************************
		// Lecture des pixels par paire pos=1 -> ..XX pos=3 -> XX..
		// Index de couleur par pixel :
		// 0-15 couleur utile
		// 16-255 considéré comme couleur transparente
		// **************************************************************

		for (int pixel = (width*height)-1; pixel >= 0; pixel = ((row-1)*width)+col-1) {
			
			// Initialisation en début de paire
			if (chunk == pos) {
				rightAlphaPxl = false;
				leftAlphaPxl = false;
			}
			
			// Lecture des pixels une paire sur deux, la paire lue dépend du paramètre d'entrée pos
			if (chunk == pos || chunk == pos+1){
				
				System.out.println("pos <"+pos+"> pixel <"+pixel+"> valeur <"+pixels[pixel]+">");
				
				// On ignore les paires de pixel transparents
				if (pixel > 0 && ((chunk == pos) && ((int) pixels[pixel]<0 || (int) pixels[pixel]>15) && ((int) pixels[pixel-1]<0 || (int) pixels[pixel-1]>15)) ) {
					
					// Gestion du LEAS de début si on commence par du transparent
					if (pixel > (width*height)-4) {
						leas = -1;
						fpixel = pixel; // intialisation des variables de travail
						fcol = col;
								
						// si les pixels suivants sont transparents par paires on avance d'autant le LEAS
						while (fpixel-5 >= 0 && ((int) pixels[fpixel-4]<0 || (int) pixels[fpixel-4]>15) &&  ((int) pixels[fpixel-5]<0 || (int) pixels[fpixel-5]>15)) {
							if (fcol-3 <= 0) { // on gère les sauts de ligne dans la suite de pixels transparents
								fcol = width-pos;
								leas += -40+width/4;
							}
							leas--;
							fpixel -= 4;
							fcol -= 4;
						}
						
						// S'il y a un pixel transparent (gauche ou droite) on avance de 1
						if ((fpixel > 4) && ((((int) pixels[fpixel-4]>=0 && (int) pixels[fpixel-4]<=15) && ((int) pixels[fpixel-5]<0 || (int) pixels[fpixel-5]>15)) ||
							(((int) pixels[fpixel-4]<0 || (int) pixels[fpixel-4]>15) && ((int) pixels[fpixel-5]>=0 && (int) pixels[fpixel-5]<=15)))) {
								leas--;
						}
							
						// Ecriture du LEAS
						if (fpixel > 3 && leas < 0) { //fpixel ? > 5
							spriteCode.add("\tLEAS "+leas+",S");
						}							
					}
					doubleFwd = 1;
				} else {
					doubleFwd = 0;

					// Construction d'une liste de pixels et transformation des pixels transparents en 0
					if ((int) pixels[pixel]>=0 && (int) pixels[pixel]<=15) {
						fdbBytes.add(Integer.toHexString((int) pixels[pixel])); // pixel plein
					} else {
						fdbBytes.add("0"); // pixel transparent
						
						// Détection de la position du pixel transparent
						if (chunk == pos+1) {
							leftAlphaPxl = true;
						} else {
							rightAlphaPxl = true;
						}
					}
					
					// **************************************************************
					// Gestion du pixel transparent à droite dans une paire de pixels
					// **************************************************************
					
					if (chunk == pos+1 && rightAlphaPxl == true) { // pixel transparent à droite
					
						spriteCode.add("\tLDA  #$0F");
						spriteCode.add("\tANDA ,S");
						spriteCode.add("\tADDA ,U+");
						
						// compter jusqu'a fin de ligne (si <160) ou max 6 octets si TG et nb octet = leas
						// on recherche une paire de pixel dont un transparent éventuellement précédé d'un maximum de 12 pixels continue
						frpixel = fpixel;
						fleas = leas;
						fpixel -= 4;
						fcol -= 4;
						while (((fcol-3 > 0 && width < 160) || (width == 160)) && fpixel-3 >= 0 && frpixel-fpixel<=14 && ((((int) pixels[fpixel-3]>=0 && (int) pixels[fpixel-3]<=15)) && ((int) pixels[fpixel-4]>=0 && (int) pixels[fpixel-4]<=15))) {
							leas--;
							fpixel -= 4;
							fcol -= 4;
						}

						// S'il n'y a pas de pixel transparent à gauche en fin de recherche on se repositionne en debut de PUL
						if 	(frpixel-fpixel == 4 || frpixel-fpixel == 32
						|| !(((int) pixels[fpixel-3]>=0 && (int) pixels[fpixel-3]<=15) && ((int) pixels[fpixel-4]<0 || (int) pixels[fpixel-4]>15))) {
							spriteCode.add("\tSTA  ,S-");
						} else {
							spriteCode.add("\tSTA  ,S");
							spriteCode.add("\tLEAS "+(-(frpixel-fpixel)/4)+",S");
						}
						
						pulBytesOld[4] = fdbBytes.get(1)+fdbBytes.get(0);
						fdbBytesResultLigne += pulBytesOld[4];
						fdbBytes.clear();
						
					} else {
						
						// **************************************************************
						// Gestion du pixel transparent à gauche dans une paire de pixels
						// **************************************************************
					
						if (leftAlphaPxl == true) { // pixel transparent à gauche
							if (fdbBytes.size() > 2 && pixel >= (width*height)-(fdbBytes.size()*2)) {
								spriteCode.add("\tLEAS -"+fdbBytes.size()/2+",S"); // Si on commence l'image, il faut se positionner correctement

							}
							spriteCode.add("\tLDA  #$F0");
							spriteCode.add("\tANDA ,S");
							spriteCode.add("\tADDA ,U+");
							if (fdbBytes.size() == 2) { // il n'y a pas d'ensemble PSH en cours on traite l'ecriture a part
								spriteCode.add("\tSTA  ,S-");
								pulBytesOld[4] = fdbBytes.get(1)+fdbBytes.get(0);
								fdbBytesResultLigne += pulBytesOld[4];
								fdbBytes.clear();
								
								// Gestion de LEAS de fin de ligne
								leas = -1;
								fpixel = pixel; // intialisation des variables de travail
								fcol = col;
										
								// Si on a atteint le début de ligne on effectue un saut en fonction de la largeur d'image
								if (fcol-3 <= 0) {
									fcol = width-pos;
									leas += -40+(width/4); // Remarque : dans le cas d'une image plein ecran leas=0
								}										
										
								// si les pixels suivants sont transparents par paires on avance d'autant le LEAS
								while (fpixel-3 >= 0 && ((int) pixels[fpixel-3]<0 || (int) pixels[fpixel-3]>15) &&  ((int) pixels[fpixel-4]<0 || (int) pixels[fpixel-4]>15)) {
									if (fcol-3 <= 0) { // on gère les sauts de ligne dans la suite de pixels transparents
										fcol = width-pos;
										leas += -40+width/4;
									}
									leas--;
									fpixel -= 4;
									fcol -= 4;
								}
								
								// S'il y a un pixel transparent (gauche ou droite) on avance de 1
								if ((fpixel > 3) && ((((int) pixels[fpixel-3]>=0 && (int) pixels[fpixel-3]<=15) && ((int) pixels[fpixel-4]<0 || (int) pixels[fpixel-4]>15)) ||
									(((int) pixels[fpixel-3]<0 || (int) pixels[fpixel-3]>15) && ((int) pixels[fpixel-4]>=0 && (int) pixels[fpixel-4]<=15)))) {
										leas--;
								}
									
								// Ecriture du LEAS
								if (fpixel > 3 && leas < 0) {
									spriteCode.add("\tLEAS "+leas+",S");
								}									
								
							} else {
								spriteCode.add("\tLEAS "+fdbBytes.size()/2+",S"); // il y a un ensemble PSH en cours on traite l'ecriture sur le PSH (Registre A)
							}
						}

						// **************************************************************
						// Gestion d'une paire de pixels pleins
						// **************************************************************
						if (chunk == pos+1 && fdbBytes.size() > 0 &&
							(fdbBytes.size() == 14 || // On a atteint le maximum de 7 octets gérable par un PSH
							(pixel-3 <= -1) || // ou fin de l'image
							(col-3 <= 0 && width < 160) || // ou fin de ligne avec image qui n'est pas plein ecran
							(pixel-3 >= 0	&& ((int) pixels[pixel-3]<0 || (int) pixels[pixel-3]>15) &&
											   ((int) pixels[pixel-4]<0 || (int) pixels[pixel-4]>15)) && // ou transparence 2 pixels dans chunk suivant
							(pixel-3 >= 0	&& ((int) pixels[pixel-4]<0 || (int) pixels[pixel-4]>15)) )) { // ou transparence à gauche dans chunk suivant
							motif = optimisationPUL (fdbBytes, pulBytesOld, fdbBytes.size()/2, leftAlphaPxl);
							String[][] result = generateCodePULPSH(fdbBytes, pulBytesOld, motif, leftAlphaPxl);
							if (!result[0][0].equals("")) {
								spriteCode.add(result[0][0]); // PUL
							}
							spriteCode.add(result[1][0]); // PSH
							fdbBytesResultLigne += result[2][0];
							pulBytesOld = result[3];		
							fdbBytes.clear();
							
							// **************************************************************
							// Gestion des sauts de ligne
							// **************************************************************
						
							leas = 0;
							fpixel = pixel; // intialisation des variables de travail
							fcol = col;

							// Si on a atteint le début de ligne on effectue un saut en fonction de la largeur d'image
							if (fcol-3 <= 0) {
								fcol = width-pos;
								leas += -40+(width/4); // Remarque : dans le cas d'une image plein ecran leas=0
							}
							
							// si les pixels suivants sont transparents par paires on avance d'autant le LEAS
							while (fpixel-3 >= 0 && ((int) pixels[fpixel-3]<0 || (int) pixels[fpixel-3]>15) &&  ((int) pixels[fpixel-4]<0 || (int) pixels[fpixel-4]>15)) {
								if (fcol-3 <= 0) { // on gère les sauts de ligne dans la suite de pixels transparents
									fcol = width-pos;
									leas += -40+width/4;
								}
								leas--;
								fpixel -= 4;
								fcol -= 4;
							}
							
							// on recherche une paire de pixel dont un seul transparent (gauche ou droite) éventuellement précédé d'un maximum de 12 pixels continus
							frpixel = fpixel;
							fleas = leas;
							while (((fcol-3 > 0 && width < 160) || (width == 160)) && fpixel-3 >= 0 && frpixel-fpixel<=28 && ((((int) pixels[fpixel-3]>=0 && (int) pixels[fpixel-3]<=15)) && ((int) pixels[fpixel-4]>=0 && (int) pixels[fpixel-4]<=15))) {
								leas--;
								fpixel -= 4;
								fcol -= 4;
							}
							
							// S'il n'y a pas de pixel transparent (gauche ou droite) on se repositionne en debut de PUL
							if 	((fpixel > 3) && (frpixel-fpixel == 24 	|| (((int) pixels[fpixel-3]<0 || (int) pixels[fpixel-3]>15) && ((int) pixels[fpixel-4]<0 || (int) pixels[fpixel-4]>15))
														|| (((int) pixels[fpixel-3]>=0 && (int) pixels[fpixel-3]<=15) && ((int) pixels[fpixel-4]>=0 && (int) pixels[fpixel-4]<=15)))) {
								leas = fleas;
							}
							
							// Si on a atteint le début de ligne on effectue un saut en fonction de la largeur d'image
							if (frpixel-fpixel == 0 && fcol-3 <= 0) {
								fcol = width-pos;
								leas += -40+(width/4);
							}
							
							// S'il y a un pixel transparent (gauche ou droite) on avance de 1
							if ((fpixel > 3) && ((((int) pixels[fpixel-3]>=0 && (int) pixels[fpixel-3]<=15) && ((int) pixels[fpixel-4]<0 || (int) pixels[fpixel-4]>15)) ||
								(((int) pixels[fpixel-3]<0 || (int) pixels[fpixel-3]>15) && ((int) pixels[fpixel-4]>=0 && (int) pixels[fpixel-4]<=15)))) {
									leas--;
							}
						
							// Ecriture du LEAS
							if (fpixel > 3 && leas < 0) {
								spriteCode.add("\tLEAS "+leas+",S");
							}
						}
					}
				}
				
				// **************************************************************
				// Copie des données en fin de ligne
				// **************************************************************
				if ((col-3 <= 0 && width < 160) || (width == 160)) {
					if (pixel>=0) {					
						fdbBytesResult += fdbBytesResultLigne;	
				System.out.println("fdbBytesResultLigne <"+fdbBytesResultLigne+">");
						fdbBytesResultLigne = "";
					}
				}
			}
			
			// **************************************************************
			// Gestion des lignes et colonnes
			// **************************************************************
				
			for (int avance = 0; avance <= doubleFwd; avance++) {
				col--;
				if (col == 0) {
					col = width;
					row--;
				}
				if (chunk == 4) {
					chunk = 1;
				} else {
					chunk++;
				}
			}
		}
		
		List<String>[] sprite = new ArrayList[2];
		sprite[0] = spriteCode;
		sprite[1] = generateDataFDB(fdbBytesResult, (pos == 1) ? 2 : 1); // Construction du code des données
		return sprite;
	}

	public ArrayList<Integer> optimisationPUL(ArrayList<String> fdbBytes, String[] pulBytesOld, int nbBytes, boolean leftAlphaPxl) {
		int somme = 0;
		int minSomme = 15;
		ArrayList<Integer> listeRegistres = new ArrayList<Integer>();
		ArrayList<Integer> minlr = new ArrayList<Integer>();
		int ilst = 0;
		Integer scr = 0;
		String[] pulBytes = new String[5];
		
		// **************************************************************
		// Test de toutes les combinaisons de registres pour savoir
		// laquelle necessite le moins de registres a recharger pour
		// construire le PUL
		// **************************************************************		
		
		for (ArrayList<Integer> lcr: regCombos.get(nbBytes)){
			for (Integer cr: lcr){
				pulBytes[cr] = "";
				if (cr == 0 || cr == 1) {
					pulBytes[cr]+=fdbBytes.get(ilst+3);
					pulBytes[cr]+=fdbBytes.get(ilst+2);
					pulBytes[cr]+=fdbBytes.get(ilst+1);
					pulBytes[cr]+=fdbBytes.get(ilst);
					ilst += 4;
				} else {
					pulBytes[cr]+=fdbBytes.get(ilst+1);
					pulBytes[cr]+=fdbBytes.get(ilst);
					ilst += 2;
				}

				if (!pulBytes[cr].equals(pulBytesOld[cr])) {
					somme += pulBytes[cr].length();
				}
				listeRegistres.add(cr);
				scr = cr;
			}
			
			// Dans le cas d'un pixel T a gauche on force la selection d'une combinaison comprenant A
			if (somme < minSomme && ((leftAlphaPxl == false) || (leftAlphaPxl == true && scr == 4))) {
				minlr = new ArrayList<>(listeRegistres);
				minSomme = somme;
			}
			
			somme = 0;
			listeRegistres.clear();
			ilst = 0;	
			pulBytes = new String[5];
		}

		return minlr;
	}

	public String[][] generateCodePULPSH(ArrayList<String> fdbBytes, String[] pulBytesOld, ArrayList<Integer> listeIndexReg, boolean leftAlphaPxl) {
		String pul = new String("");
		String psh = new String("");	
		String[] pulBytes = {"", "", "", "", ""};
		String[] pulBytesFiltered = {"", "", "", "", ""};		
		String fdbBytesResult = new String("");
		String[][] result = new String[4][];
		final String[] pulReg = {"X", "Y", "DP", "B", "A"};
		int ilst = 0;
		
		// **************************************************************
		// Construction du PUL et du PSH
		// **************************************************************		
		
		for (Integer indexReg: listeIndexReg){
			// Copie en sens inverse: 2 octets pour les deux premiers registres, 1 octet pour les 3 derniers
			// On ne copie pas le registre A si on a un pixel transparent à gauche
			if (indexReg == 0 || indexReg == 1) {
				pulBytes[indexReg]+=fdbBytes.get(ilst+3);
				pulBytes[indexReg]+=fdbBytes.get(ilst+2);
				pulBytes[indexReg]+=fdbBytes.get(ilst+1);
				pulBytes[indexReg]+=fdbBytes.get(ilst);
				ilst += 4;
			} else {
				pulBytes[indexReg]+=fdbBytes.get(ilst+1);
				pulBytes[indexReg]+=fdbBytes.get(ilst);
				ilst += 2;
			}

			// Dans le cas d'un pixel transparent à gauche traité dans le PSH
			// on ne renseigne pas le registre sur le PUL car traité dans un LDA
			if (!pulBytes[indexReg].equals(pulBytesOld[indexReg]) && !(leftAlphaPxl == true && indexReg == 4)) {
				if (pul.equals("")) {
					pul += "\tPULU ";
				} else {
					pul += ",";
				}
				pul += pulReg[indexReg];
			}
			
			if (!pulBytes[indexReg].equals(pulBytesOld[indexReg]) || (leftAlphaPxl == true && indexReg == 4)) {
				pulBytesOld[indexReg]=pulBytes[indexReg];
				pulBytesFiltered[indexReg] += pulBytes[indexReg];
			}
			
			if (psh.equals("")) {
				psh += "\tPSHS ";
			} else {
				psh += ",";
			}
			psh += pulReg[indexReg];
		}
		
		// Enregistre les données FDB en sens inverse et filtrées
		for(int i = listeIndexReg.size()-1 ; i >=0 ; i--) {
				fdbBytesResult += pulBytesFiltered[listeIndexReg.get(i)];
		}
	
		result[0] = new String[]{pul};
		result[1] = new String[]{psh};
		result[2] = new String[]{fdbBytesResult};
		result[3] = pulBytesOld;
		return result;
	}

	public void ComputeRegCombos() {
		final int[] registres = {2, 2, 1, 1, 1}; // taille en octet de chaque registre dans l'ordre du PSH X, Y, DP, B, A
		ArrayList<Integer> registresCourant = new ArrayList<Integer>();
		regCombos.clear();
		
		// **************************************************************
		// Construit un tableau dont l'indice est un nombre d'octet
		// de 0 à 7. Chaque indice contient les listes de toutes les
		// combinaisons des registres dont la taille totale correspond
		// au nombre d'octets défini dans l'indice.
		// **************************************************************	
		
		for (int i = 0; i < 8; i++) {
			regCombos.add(new ArrayList<ArrayList<Integer>>());
		}
		
		// génération des combinaisons binaires pour 5 bits
		for (int nbe = 0, nbBytes = 0, x = 0; nbe < 32; nbe++) {
			nbBytes = 0;
			for (int i = 4, j = 0; i >= 0; i--) {
				x = ((nbe & (1 << i)) != 0) ? 1 : 0;;
				if (x==1) {
					registresCourant.add(j);
					nbBytes += registres[j];
				}
				j++;
			}
			regCombos.get(nbBytes).add(registresCourant);
			registresCourant =  new ArrayList<Integer>();
		}

		return;
	}		

	public List<String> generateDataFDB(String pixels, int x) {
		int bitIndex = 0;
		List<String> spriteData = new ArrayList<String>();
		String dataLine = new String();
		
		// **************************************************************
		// Construit un tableau de données en assembleur
		// **************************************************************	
	
		spriteData.add(dataLabel+"_"+x);
		
		for (int i = 0; i < pixels.length(); i++) {
			bitIndex++;
			if (bitIndex == 1) {
				dataLine = "\tFDB $";
			}

			dataLine += pixels.charAt(i);
				
			if (bitIndex == 4) {
				spriteData.add(dataLine);
				bitIndex = 0;
			}
		}
		
		// On complete le mot pour la dernière ligne de données
		if (bitIndex > 0 ) {
			while (bitIndex < 4) {
				dataLine += "0";
				bitIndex++;
			}
			spriteData.add(dataLine);
		}

		return spriteData;
	}
 
	public String removeExtension(String s) {
	
		String separator = System.getProperty("file.separator");
		String filename;
	
		// Remove the path upto the filename.
		int lastSeparatorIndex = s.lastIndexOf(separator);
		if (lastSeparatorIndex == -1) {
			filename = s;
		} else {
			filename = s.substring(lastSeparatorIndex + 1);
		}
	
		// Remove the extension.
		int extensionIndex = filename.lastIndexOf(".");
		if (extensionIndex == -1)
			return filename;
	
		return filename.substring(0, extensionIndex);
	}
	
	public String getName() {
		return spriteName;
	}

	public List<String> getCompiledCode(int i) {
		return (i == 1) ? spriteCode2 : spriteCode1;
	}
	
	public List<String> getCompiledData(int i) {
		return (i == 1) ? spriteData2 : spriteData1;
	}
	
	public List<String> getCodeHeader(int pos) {
		List<String> code = new ArrayList<String>();
		code.add(drawLabel);
		code.add("\tPSHS U,DP");
		code.add("\tSTS >SSAVE");
		code.add("");
		code.add("\tLDS >"+posLabel);
		code.add("\tLDU #"+dataLabel+"_"+pos);
		code.add("");
		return code;
	}

	public List<String> getCodeSwitchData(int pos) {
		List<String> code = new ArrayList<String>();
		code.add("");
		code.add("\tLDS >"+posLabel);
		code.add("\tLEAS 8192,S");
		code.add("\tLDU #"+dataLabel+"_"+pos);
		code.add("");
		return code;
	}
	
	public List<String> getCodeFooter() {
		List<String> code = new ArrayList<String>();
		code.add("");
		code.add("\tLDS  >SSAVE");
		code.add("\tPULS U,DP");
		code.add("\tRTS");
		code.add("");
		return code;
	}
	
	public List<String> getCodeDataPos() {
		List<String> code = new ArrayList<String>();
		code.add(posLabel);
		code.add("\tFDB $1F40");
		code.add("");
		return code;
	}

	public List<String> getCodePalette() {
		List<String> code = new ArrayList<String>();
		
		code.add("TABPALETTE");
		// Construction de la palette de couleur
        for (int j=0; j<16; j++) {
			Color couleur = new Color(colorModel.getRGB(j));
			code.add("\tFDB $0"	+Integer.toHexString((int) Math.round(15*Math.pow((couleur.getBlue()/255.0),3)))
								+Integer.toHexString((int) Math.round(15*Math.pow((couleur.getGreen()/255.0),3)))
								+Integer.toHexString((int) Math.round(15*Math.pow((couleur.getRed()/255.0),3)))
								+"\t* index:"+String.format("%-2.2s", j)+" R:"+String.format("%-3.3s", couleur.getRed())+" V:"+String.format("%-3.3s", couleur.getGreen())+" B:"+String.format("%-3.3s", couleur.getBlue()));
		}
		code.add("FINTABPALETTE");
		return code;
	}
	
	//public static BufferedImage rgbaToIndexedBufferedImage(BufferedImage sourceBufferedImage) {
	//	//With this constructor we create an indexed bufferedimage with the same dimensiosn and with a default 256 color model
	//	BufferedImage indexedImage= new BufferedImage(sourceBufferedImage.getWidth(),sourceBufferedImage.getHeight(),BufferedImage.TYPE_BYTE_INDEXED);
	//
	//
	//	ColorModel cm = indexedImage.getColorModel();
	//	IndexColorModel icm=(IndexColorModel) cm;
	//
	//	int size=icm.getMapSize();
	//
	//	byte[] reds = new byte[size];
	//	byte[] greens = new byte[size];
	//	byte[] blues = new byte[size];
	//	icm.getReds(reds);
	//	icm.getGreens(greens);
	//	icm.getBlues(blues);
	//
	//	WritableRaster raster=indexedImage.getRaster();
	//	int pixel = raster.getSample(0, 0, 0); 
	//	IndexColorModel icm2 = new IndexColorModel(8, size, reds, greens, blues,pixel);
	//	indexedImage=new BufferedImage(icm2, raster,sourceBufferedImage.isAlphaPremultiplied(), null);
	//	indexedImage.getGraphics().drawImage(sourceBufferedImage, 0, 0, null);
	//	return indexedImage;
	//}
}
