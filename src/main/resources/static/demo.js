"use strict";
const example=document.getElementById("example"), button=document.getElementById("compare"), statusText=document.getElementById("status");
let generation=0;
async function load(compare=false) {
  const current=++generation;
  button.disabled=true;
  document.getElementById("result").hidden=true;
  statusText.textContent=compare ? "Comparing fictional documents…" : "Loading fictional documents…";
  try {
    const response=await fetch("/api/demo?example="+encodeURIComponent(example.value),{credentials:"omit"});
    if(!response.ok) throw new Error("The demo could not load (HTTP "+response.status+"). Please retry.");
    const data=await response.json();
    if(current!==generation) return;
    document.getElementById("si").textContent=data.si;
    document.getElementById("bl").textContent=data.bl || "No draft BL attached.";
    if(compare) {
      document.getElementById("summary").textContent=data.result.summary;
      const rows=document.getElementById("rows");rows.replaceChildren();
      for(const field of data.result.fields) {
        const row=document.createElement("tr");
        for(const text of [field.field.replaceAll("_"," "),field.si,field.bl,field.status+": "+field.detail]) {
          const cell=document.createElement("td");cell.textContent=text;row.append(cell);
        }
        for(const [index,evidence] of [[1,field.si_evidence],[2,field.bl_evidence]]) {
          const details=document.createElement("details"), label=document.createElement("summary"), quote=document.createElement("p");
          label.textContent="Source quotation";quote.textContent=evidence;details.append(label,quote);row.children[index].append(details);
        }
        rows.append(row);
      }
      document.getElementById("result").hidden=false;
    }
    statusText.textContent=compare ? "Comparison completed using Java rules. No AI request was made." : "Documents ready. Select Compare documents to check them.";
  } catch(error) { if(current===generation) statusText.textContent=error.message; }
  finally { if(current===generation) button.disabled=false; }
}
example.addEventListener("change",()=>load());button.addEventListener("click",()=>load(true));load();
